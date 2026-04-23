package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void throwerlist$stylePlayerListName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (!NameStyler.INSTANCE.hasStyledProfile(entry.getProfile())) {
            return;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDecorations(current);
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }

    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"
        ),
        index = 1
    )
    private Text throwerlist$styleRenderedTabName(Text text) {
        if (text == null) {
            return text;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDecorations(text);
        return styled != text ? styled : text;
    }
}
