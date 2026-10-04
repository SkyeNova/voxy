package me.cortex.voxy.client.compat;

import java.io.File;

public interface IFlashbackMeta {
    void setVoxyPath(File path);
    File getVoxyPath();
    void setVoxySnapshotPath(File path);
    File getVoxySnapshotPath();
}
