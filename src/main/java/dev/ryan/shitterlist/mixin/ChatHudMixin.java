package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @ModifyArg(
        method = "addVisibleMessage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/ChatHudLine$Visible;<init>(ILnet/minecraft/text/OrderedText;Lnet/minecraft/client/gui/hud/MessageIndicator;Z)V"
        ),
        index = 1
    )
    private OrderedText throwerlist$styleVisibleChatLine(OrderedText text) {
        if (text == null || !NameStyler.INSTANCE.hasGradientStyles()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }
}
