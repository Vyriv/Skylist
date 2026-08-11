package dev.ryan.playerlist.mixin;

import com.mojang.authlib.GameProfile;
import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.OwnerCape;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public abstract class PlayerListEntryMixin {
    @Shadow @Final private GameProfile profile;
    @Shadow private Component tabListDisplayName;

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void playerlist$styleDisplayName(CallbackInfoReturnable<Component> cir) {
        Component current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (!NameStyler.INSTANCE.hasDisplayProfile(this.profile)) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }

    @Inject(method = "setTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void playerlist$styleIncomingDisplayName(Component text, CallbackInfo ci) {
        if (text == null) {
            return;
        }
        if (NameStyler.INSTANCE.hasAnimatedStyledProfile(this.profile)) {
            return;
        }

        Component current = text;
        if (NameStyler.INSTANCE.hasDisplayProfile(this.profile)) {
            current = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        }
        if (current == text) {
            return;
        }

        this.tabListDisplayName = current;
        ci.cancel();
    }

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void playerlist$applyCustomCape(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin styled = OwnerCape.INSTANCE.applyCustomCape(this.profile, cir.getReturnValue());
        if (styled != cir.getReturnValue()) {
            cir.setReturnValue(styled);
        }
    }
}
