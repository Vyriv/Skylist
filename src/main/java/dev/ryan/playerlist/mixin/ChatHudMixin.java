package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Component playerlist$styleChatMessage(Component text) {
        if (text == null || !NameStyler.INSTANCE.hasChatHeaderStyles()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToChatHeader(text);
    }

    @ModifyArg(
        method = "addMessageToDisplayQueue",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;<init>(Lnet/minecraft/client/multiplayer/chat/GuiMessage;Lnet/minecraft/util/FormattedCharSequence;Z)V"
        ),
        index = 1
    )
    private FormattedCharSequence playerlist$styleVisibleChatLine(FormattedCharSequence text) {
        if (text == null || (!NameStyler.INSTANCE.hasChatHeaderStyles() && !NameStyler.INSTANCE.hasGradientStyles())) {
            return text;
        }

        FormattedCharSequence styled = text;
        if (NameStyler.INSTANCE.hasChatHeaderStyles()) {
            styled = NameStyler.INSTANCE.applyChatHeaderToOrderedText(styled);
        }
        if (NameStyler.INSTANCE.hasGradientStyles()) {
            styled = NameStyler.INSTANCE.applyGradientToOrderedText(styled);
        }
        return styled;
    }
}
