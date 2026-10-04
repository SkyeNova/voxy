package me.cortex.voxy.client.mixin.flashback;

import com.google.gson.JsonObject;
import com.moulberry.flashback.record.FlashbackMeta;
import me.cortex.voxy.client.compat.IFlashbackMeta;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

@Mixin(value = FlashbackMeta.class, remap = false)
public class MixinFlashbackMeta implements IFlashbackMeta {
    @Unique private File voxyPath;
    @Unique private File voxySnapshotPath;

    @Override
    public void setVoxyPath(File path) {
        this.voxyPath = path;
    }

    @Override
    public File getVoxyPath() {
        return this.voxyPath;
    }

    @Override
    public void setVoxySnapshotPath(File path) {
        this.voxySnapshotPath = path;
    }

    @Override
    public File getVoxySnapshotPath() {
        return this.voxySnapshotPath;
    }

    @Inject(method = "toJson", at = @At("RETURN"))
    private void voxy$injectSaveVoxyPath(CallbackInfoReturnable<JsonObject> cir) {
        var val = cir.getReturnValue();
        if (val != null && this.voxyPath != null) {
            val.addProperty("voxy_storage_path", this.voxyPath.getAbsoluteFile().getPath());
        }
        if (val != null && this.voxySnapshotPath != null) {
            val.addProperty("voxy_snapshot_path", this.voxySnapshotPath.getAbsoluteFile().getPath());
        }
    }

    @Inject(method = "fromJson", at = @At("RETURN"))
    private static void voxy$injectGetVoxyPath(JsonObject meta, CallbackInfoReturnable<FlashbackMeta> cir) {
        var val = cir.getReturnValue();
        if (val != null && meta != null) {
            if (meta.has("voxy_storage_path")) {
                ((IFlashbackMeta)val).setVoxyPath(new File(meta.get("voxy_storage_path").getAsString()));
            }
            if (meta.has("voxy_snapshot_path")) {
                ((IFlashbackMeta)val).setVoxySnapshotPath(new File(meta.get("voxy_snapshot_path").getAsString()));
            }
        }
    }
}
