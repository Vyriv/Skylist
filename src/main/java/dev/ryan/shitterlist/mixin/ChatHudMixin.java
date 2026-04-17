package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Text throwerlist$styleChatMessage(Text text) {
        if (text == null || !NameStyler.INSTANCE.containsStyledTargetName(text.getString())) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToChatHeader(text);
    }
}
