package me.cortex.voxy.client.compat;

import com.google.gson.JsonParser;
import com.moulberry.flashback.Flashback;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

//Per replay copies of the voxy save folder, so a replay edited later still shows the terrain from when it was recorded.
// RocksDB never modifies an SST file after writing it, so they are hard linked instead of copied, only data that
// changed after the snapshot costs extra disk
public final class ReplayVoxySnapshots {
    //Long enough that a recording flashback has not saved yet (e.g. pending recovery after a crash) is not deleted
    private static final long ORPHAN_GRACE_MILLIS = 24L * 60 * 60 * 1000;
    private static final AtomicBoolean CLEANED = new AtomicBoolean();

    private ReplayVoxySnapshots() {}

    public static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("replay-snapshots");
    }

    public static Path pathFor(UUID replay) {
        return root().resolve(replay.toString());
    }

    public static boolean create(Path liveBase, Path target) {
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            long start = System.currentTimeMillis();
            deleteTree(tmp);
            deleteTree(target);
            Files.createDirectories(tmp);
            try (var children = Files.list(liveBase)) {
                for (var child : children.toList()) {
                    var dst = tmp.resolve(child.getFileName().toString());
                    if (Files.isDirectory(child)) {
                        snapshotWorldFolder(child, dst);
                    } else {
                        Files.copy(child, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            //Only a complete snapshot is ever visible under the real name
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            Logger.info("Created voxy replay snapshot " + target + " in " + (System.currentTimeMillis() - start) + "ms");
            toast(Component.literal("Voxy terrain saved with replay"));
            return true;
        } catch (Exception e) {
            Logger.error("Failed to create voxy replay snapshot at " + target + ", the replay will use the live voxy data", e);
            try {
                deleteTree(tmp);
            } catch (IOException ignored) {}
            toast(Component.literal("Voxy replay snapshot FAILED, see log"));
            return false;
        }
    }

    private static void snapshotWorldFolder(Path src, Path dst) throws Exception {
        //Layout is <world id>/storage/<rocksdb files>
        Files.createDirectories(dst);
        try (var children = Files.list(src)) {
            for (var child : children.toList()) {
                var childDst = dst.resolve(child.getFileName().toString());
                if (Files.isDirectory(child) && Files.exists(child.resolve("CURRENT"))) {
                    snapshotRocksDB(child, childDst);
                } else if (Files.isDirectory(child)) {
                    snapshotWorldFolder(child, childDst);
                } else {
                    Files.copy(child, childDst, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void snapshotRocksDB(Path db, Path dst) throws Exception {
        var open = RocksDBStorageBackend.getOpen(db);
        if (open != null) {
            //Live database, let rocksdb make a consistent checkpoint
            open.createCheckpoint(dst);
            return;
        }
        //Closed database, its files are not changing so link the immutable ones and copy the rest
        Files.createDirectories(dst);
        try (var files = Files.list(db)) {
            for (var file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.equals("LOCK") || Files.isDirectory(file)) continue;
                var out = dst.resolve(name);
                if (name.endsWith(".sst") || name.endsWith(".blob")) {
                    try {
                        Files.createLink(out, file);
                        continue;
                    } catch (UnsupportedOperationException | IOException e) {
                        //Different filesystem or no hard link support, fall through to a copy
                    }
                }
                Files.copy(file, out, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /**
     * Deletes snapshots whose replay no longer exists. Runs once per session, in the background.
     */
    public static void cleanupOrphansAsync() {
        if (!FlashbackCompat.FLASHBACK_INSTALLED || !CLEANED.compareAndSet(false, true)) return;
        var thread = new Thread(ReplayVoxySnapshots::cleanupOrphans, "Voxy replay snapshot cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void cleanupOrphans() {
        try {
            var root = root();
            if (!Files.isDirectory(root)) return;
            Set<String> referenced = referencedReplayIds();
            if (referenced == null) return;
            long now = System.currentTimeMillis();
            try (var snapshots = Files.list(root)) {
                for (var snapshot : snapshots.toList()) {
                    String name = snapshot.getFileName().toString();
                    String id = name.endsWith(".tmp") ? name.substring(0, name.length() - 4) : name;
                    if (referenced.contains(id) && !name.endsWith(".tmp")) continue;
                    if (now - Files.getLastModifiedTime(snapshot).toMillis() < ORPHAN_GRACE_MILLIS) continue;
                    Logger.info("Deleting voxy replay snapshot " + name + ", its replay no longer exists");
                    deleteTree(snapshot);
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to clean up voxy replay snapshots", e);
        }
    }

    //null if the replay folder could not be read, nothing must be deleted then
    private static Set<String> referencedReplayIds() {
        Path replays;
        try {
            replays = Flashback.getReplayFolder();
        } catch (Throwable e) {
            Logger.error("Could not get the flashback replay folder", e);
            return null;
        }
        Set<String> ids = new HashSet<>();
        if (replays == null || !Files.isDirectory(replays)) return null;
        try (var walk = Files.walk(replays)) {
            for (var file : walk.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".zip")) {
                    try (var zip = new ZipFile(file.toFile())) {
                        var entry = zip.getEntry("metadata.json");
                        if (entry == null) continue;
                        try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                            addId(ids, JsonParser.parseReader(reader).getAsJsonObject());
                        }
                    } catch (Exception e) {
                        //An unreadable replay could still reference a snapshot, keep everything
                        Logger.warn("Could not read replay " + file + ", skipping snapshot cleanup", e);
                        return null;
                    }
                } else if (name.equals("metadata.json")) {
                    addId(ids, JsonParser.parseString(Files.readString(file)).getAsJsonObject());
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to scan flashback replays", e);
            return null;
        }
        return ids;
    }

    private static void addId(Set<String> ids, com.google.gson.JsonObject meta) {
        if (meta.has("uuid")) ids.add(meta.get("uuid").getAsString());
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                //Deleting a hard link only removes this name, the live database keeps its file
                Files.delete(p);
            }
        }
    }

    private static void toast(Component title) {
        var mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP, title, Component.literal("Flashback")));
    }
}
