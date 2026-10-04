package me.cortex.voxy.common.world.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Loads the id mappings of real voxy saves through the 26.3 migration path.
 * Point -Dvoxy.realdata at a COPY of a .voxy/saves folder, the databases are written to.
 * Skipped when the property is not set.
 */
public class RealWorldMappingMigrationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void realSavesMigrateWithoutLosingBlocks() throws IOException {
        String root = System.getProperty("voxy.realdata");
        Assumptions.assumeTrue(root != null && !root.isEmpty(), "voxy.realdata not set");

        List<Path> dbs = new ArrayList<>();
        try (var walk = Files.walk(Path.of(root))) {
            walk.filter(p -> p.getFileName().toString().equals("CURRENT")).forEach(p -> dbs.add(p.getParent()));
        }
        Assumptions.assumeFalse(dbs.isEmpty(), "no rocksdb databases found");

        int totalFailed = 0;
        int totalRenamed = 0;
        for (var db : dbs) {
            List<String> first;
            int failed;
            {
                var storage = new RocksDBStorageBackend(db.toString());
                var legacyNames = legacyNames(storage);
                var mapper = new Mapper(storage);
                first = describe(mapper);
                failed = countFailed(mapper);
                totalRenamed += countRenamed(mapper, legacyNames);
                storage.close();
            }
            //Reload after the migration resave, everything must come back identical
            List<String> second;
            {
                var storage = new RocksDBStorageBackend(db.toString());
                var mapper = new Mapper(storage);
                second = describe(mapper);
                storage.close();
            }
            System.out.println("[realdata] " + db + " states=" + first.size() + " failed=" + failed);
            assertEquals(first, second, "mappings changed between the migration load and the reload: " + db);
            totalFailed += failed;
        }
        assertEquals(0, totalFailed, "some saved blockstates could not be migrated");
        assertEquals(0, totalRenamed, "some saved blockstates changed block during migration");
    }

    private static Int2ObjectOpenHashMap<String> legacyNames(RocksDBStorageBackend storage) throws IOException {
        var out = new Int2ObjectOpenHashMap<String>();
        for (var entry : storage.getIdMappingsData().int2ObjectEntrySet()) {
            if ((entry.getIntKey() >>> 30) != 1) continue;
            var compound = NbtIo.readCompressed(new ByteArrayInputStream(entry.getValue()), NbtAccounter.unlimitedHeap());
            var name = compound.getCompound("block_state").flatMap(c -> c.getString("Name"));
            name.ifPresent(n -> out.put(entry.getIntKey() & ((1 << 30) - 1), n));
        }
        return out;
    }

    private static int countRenamed(Mapper mapper, Int2ObjectOpenHashMap<String> legacyNames) {
        int renamed = 0;
        for (var entry : legacyNames.int2ObjectEntrySet()) {
            String now = BuiltInRegistries.BLOCK.getKey(mapper.getBlockStateFromBlockId(entry.getIntKey()).getBlock()).toString();
            //Blocks that do not exist anymore are expected to be renamed by the datafixer
            boolean stillExists = BuiltInRegistries.BLOCK.containsKey(Identifier.parse(entry.getValue()));
            if (stillExists && !now.equals(entry.getValue())) {
                System.out.println("[realdata] RENAMED id " + entry.getIntKey() + " " + entry.getValue() + " -> " + now);
                renamed++;
            }
        }
        return renamed;
    }

    private static List<String> describe(Mapper mapper) {
        var out = new ArrayList<String>();
        for (int i = 0; i < mapper.getBlockStateCount(); i++) {
            out.add(i + "=" + mapper.getBlockStateFromBlockId(i));
        }
        return out;
    }

    private static int countFailed(Mapper mapper) {
        int failed = 0;
        //Id 0 is air by definition, any other air entry is a failed decode
        for (int i = 1; i < mapper.getBlockStateCount(); i++) {
            if (mapper.getBlockStateFromBlockId(i).isAir()) {
                Logger.error("Failed decode at id " + i);
                failed++;
            }
        }
        return failed;
    }
}
