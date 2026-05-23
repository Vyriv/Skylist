package dev.ryan.playerlist.mixin;

import com.mojang.authlib.GameProfile;
import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.OwnerCape;
import dev.ryan.playerlist.SkylistPresenceManager;
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
    private void playerlist$styleDisplayName(CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (!NameStyler.INSTANCE.hasDisplayProfile(this.profile)) {
            return;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        Text identified = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, this.profile);
        if (identified != current) {
            cir.setReturnValue(identified);
        }
    }

    @Inject(method = "setDisplayName", at = @At("HEAD"), cancellable = true)
    private void playerlist$styleIncomingDisplayName(Text text, CallbackInfo ci) {
        if (text == null) {
            return;
        }
        if (NameStyler.INSTANCE.hasAnimatedStyledProfile(this.profile)) {
            return;
        }

        Text current = text;
        if (NameStyler.INSTANCE.hasDisplayProfile(this.profile)) {
            current = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        }
        current = SkylistPresenceManager.INSTANCE.applyIdentifier(current, this.profile);
        if (current == text) {
            return;
        }

        this.displayName = current;
        ci.cancel();
    }

    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void playerlist$applyCustomCape(CallbackInfoReturnable<SkinTextures> cir) {
        SkinTextures styled = OwnerCape.INSTANCE.applyCustomCape(this.profile, cir.getReturnValue());
        if (styled != cir.getReturnValue()) {
            cir.setReturnValue(styled);
        }
    }
}
