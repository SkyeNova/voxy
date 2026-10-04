package me.cortex.voxy.common.config.storage.rocksdb;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replay snapshots checkpoint a live database. Unchanged SST files must be shared (hard linked), later writes to the
 * live database must not show up in the snapshot, and deleting the snapshot must not touch the live database.
 * Uses -Dvoxy.realdata (a COPY of .voxy/saves), skipped otherwise.
 */
public class RocksDBCheckpointTest {
    @Test
    void checkpointIsSharedAndIsolated() throws Exception {
        String root = System.getProperty("voxy.realdata");
        Assumptions.assumeTrue(root != null && !root.isEmpty(), "voxy.realdata not set");
        Path db;
        try (var walk = Files.walk(Path.of(root))) {
            //Largest database gives the most meaningful sharing check
            db = walk.filter(p -> p.getFileName().toString().equals("CURRENT")).map(Path::getParent)
                    .max(Comparator.comparingLong(RocksDBCheckpointTest::size)).orElse(null);
        }
        Assumptions.assumeTrue(db != null);
        //Work on a private copy, the test writes to the database
        Path work = Files.createTempDirectory(Path.of(root), "checkpoint-test");
        Path src = db;
        db = work.resolve("storage");
        Files.createDirectories(db);
        try (var files = Files.list(src)) {
            for (var f : files.toList()) if (Files.isRegularFile(f)) Files.copy(f, db.resolve(f.getFileName().toString()));
        }
        Path snap = work.resolve("storage-snapshot-test");

        int marker = (1 << 30) | 0x3FFFFFF0;
        var live = new RocksDBStorageBackend(db.toString());
        assertSame(live, RocksDBStorageBackend.getOpen(db));
        int before = live.getIdMappingsData().size();
        live.createCheckpoint(snap);

        int shared = 0, ssts = 0;
        try (var files = Files.list(snap)) {
            for (var f : files.toList()) {
                if (!f.getFileName().toString().endsWith(".sst")) continue;
                ssts++;
                var orig = db.resolve(f.getFileName().toString());
                if (Files.exists(orig) && Files.isSameFile(orig, f)) shared++;
            }
        }
        System.out.println("[checkpoint] " + db + " size=" + size(db) / (1024 * 1024) + "MB ssts=" + ssts + " hardlinked=" + shared);
        assertTrue(ssts == 0 || shared == ssts, "SST files were copied instead of hard linked");

        live.putIdMapping(marker, ByteBuffer.wrap(new byte[]{1, 2, 3}));
        live.close();
        assertNull(RocksDBStorageBackend.getOpen(db));

        var snapshot = new RocksDBStorageBackend(snap.toString());
        assertEquals(before, snapshot.getIdMappingsData().size(), "snapshot changed after later live writes");
        assertFalse(snapshot.getIdMappingsData().containsKey(marker));
        snapshot.close();

        deleteTree(snap);
        var reopened = new RocksDBStorageBackend(db.toString());
        assertEquals(before + 1, reopened.getIdMappingsData().size(), "live database damaged by deleting the snapshot");
        reopened.close();
        deleteTree(work);
    }

    private static long size(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (var f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
