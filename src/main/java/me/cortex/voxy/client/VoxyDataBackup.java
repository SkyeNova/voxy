package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

//Copies a save folder the first time it is opened by a new minecraft data version, before any storage is opened,
// so a broken migration can be undone by dragging the copy back
public final class VoxyDataBackup {
    private static final String MARKER = "voxy-data-version.txt";

    private VoxyDataBackup() {}

    /**
     * @return false if a backup was needed but could not be made, voxy should not open the storage
     */
    public static boolean backupIfNeeded(Path savePath) {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        var marker = savePath.resolve(MARKER);
        try {
            if (!Files.isDirectory(savePath) || isEmpty(savePath)) {
                //Nothing stored yet, nothing to back up
                Files.createDirectories(savePath);
                Files.writeString(marker, Integer.toString(current));
                return true;
            }

            int stored = -1;
            if (Files.exists(marker)) {
                try {
                    stored = Integer.parseInt(Files.readString(marker).trim());
                } catch (NumberFormatException e) {
                    Logger.warn("Invalid voxy data version marker in " + savePath + ", backing up again");
                }
            }
            if (stored >= current) {
                return true;
            }

            String from = stored == -1 ? "unversioned" : Integer.toString(stored);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            //Singleplayer data lives in <world>/voxy, name the backup after the world instead
            String name = savePath.getFileName().toString();
            if (name.equals("voxy") && savePath.getParent() != null) {
                name = savePath.getParent().getFileName().toString();
            }
            var target = Minecraft.getInstance().gameDirectory.toPath().resolve("voxy-backups")
                    .resolve(name + "_" + from + "-to-" + current + "_" + stamp);

            Logger.info("Backing up voxy data " + savePath + " to " + target + " before the first load on data version " + current);
            long start = System.currentTimeMillis();
            copyTree(savePath, target);
            Logger.info("Voxy backup finished in " + (System.currentTimeMillis() - start) + "ms");

            Files.writeString(marker, Integer.toString(current));
            toast(Component.literal("Voxy backup created"), Component.literal("voxy-backups/" + target.getFileName()));
            return true;
        } catch (Exception e) {
            Logger.error("Failed to back up voxy data at " + savePath + ", voxy will not load this world to keep it safe", e);
            toast(Component.literal("Voxy backup FAILED"), Component.literal("Voxy disabled for this world, see log"));
            return false;
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var list = Files.list(dir)) {
            return list.findAny().isEmpty();
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                //A stale rocksdb LOCK is empty and useless in a backup
                if (file.getFileName().toString().equals("LOCK")) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void toast(Component title, Component body) {
        var mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP, title, body));
    }
}
