package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.SkylistPresenceManager;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void playerlist$stylePlayerListName(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
        Component current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (!NameStyler.INSTANCE.hasDisplayProfile(entry.getProfile())) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        Component identified = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, entry.getProfile());
        if (identified != current) {
            cir.setReturnValue(identified);
        }
    }

}
