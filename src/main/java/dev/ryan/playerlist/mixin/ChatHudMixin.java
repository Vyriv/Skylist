package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Text playerlist$styleChatMessage(Text text) {
        if (text == null || !NameStyler.INSTANCE.hasChatHeaderStyles()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToChatHeader(text);
    }

    @ModifyArg(
        method = "addVisibleMessage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/ChatHudLine$Visible;<init>(ILnet/minecraft/text/OrderedText;Lnet/minecraft/client/gui/hud/MessageIndicator;Z)V"
        ),
        index = 1
    )
    private OrderedText playerlist$styleVisibleChatLine(OrderedText text) {
        if (text == null || (!NameStyler.INSTANCE.hasChatHeaderStyles() && !NameStyler.INSTANCE.hasGradientStyles())) {
            return text;
        }

        OrderedText styled = text;
        if (NameStyler.INSTANCE.hasChatHeaderStyles()) {
            styled = NameStyler.INSTANCE.applyChatHeaderToOrderedText(styled);
        }
        if (NameStyler.INSTANCE.hasGradientStyles()) {
            styled = NameStyler.INSTANCE.applyGradientToOrderedText(styled);
        }
        return styled;
    }
}
