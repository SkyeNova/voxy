package me.cortex.voxy.client.mixin.flashback;

import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.compat.IFlashbackMeta;
import me.cortex.voxy.client.compat.ReplayVoxySnapshots;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(value = Recorder.class, remap = false)
public class MixinFlashbackRecorder {
    @Shadow @Final private FlashbackMeta metadata;

    @Inject(method = "finish", at = @At("HEAD"))
    private void voxy$snapshotOnFinish(CallbackInfoReturnable<Path> cir) {
        var meta = (IFlashbackMeta)this.metadata;
        if (meta.getVoxyPath() != null && meta.getVoxySnapshotPath() != null) {
            ReplayVoxySnapshots.create(meta.getVoxyPath().toPath(), meta.getVoxySnapshotPath().toPath());
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getStoragePath(RegistryAccess registryAccess, CallbackInfo retInf) {
        if (VoxyCommon.isAvailable()) {
            var instance = VoxyCommon.getInstance();
            if (instance instanceof VoxyClientInstance ci) {
                ((IFlashbackMeta)this.metadata).setVoxyPath(ci.getStorageBasePath().toFile());
                //Metadata is written during recording, so record where the snapshot will be up front.
                // If it is never made the replay falls back to the live data
                if (VoxyConfig.CONFIG.flashbackVoxySnapshots) {
                    ((IFlashbackMeta)this.metadata).setVoxySnapshotPath(ReplayVoxySnapshots.pathFor(this.metadata.replayIdentifier).toFile());
                }
            }
        }
    }
}
