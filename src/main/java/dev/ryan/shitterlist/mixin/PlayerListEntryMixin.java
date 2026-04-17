package dev.ryan.throwerlist.mixin;

import com.mojang.authlib.GameProfile;
import dev.ryan.throwerlist.NameStyler;
import dev.ryan.throwerlist.OwnerCape;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Shadow @Final private GameProfile profile;
    @Shadow private Text displayName;

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void throwerlist$styleDisplayName(CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (NameStyler.INSTANCE.hasStyledProfile(this.profile)) {
            current = NameStyler.INSTANCE.applyNameplateDecorations(current);
        }
        cir.setReturnValue(current);
    }

    @Inject(method = "setDisplayName", at = @At("HEAD"), cancellable = true)
    private void throwerlist$styleIncomingDisplayName(Text text, CallbackInfo ci) {
        if (text == null) {
            return;
        }

        Text current = text;
        if (NameStyler.INSTANCE.hasStyledProfile(this.profile)) {
            current = NameStyler.INSTANCE.applyNameplateDecorations(current);
        }
        if (current == text) {
            return;
        }

        this.displayName = current;
        ci.cancel();
    }

    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void throwerlist$applyCustomCape(CallbackInfoReturnable<SkinTextures> cir) {
        if (!NameStyler.INSTANCE.isTargetProfile(this.profile)) {
            return;
        }

        cir.setReturnValue(OwnerCape.INSTANCE.applyCustomCape(this.profile, cir.getReturnValue()));
    }
}
