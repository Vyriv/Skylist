package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
public abstract class InGameHudMixin {
    @Inject(
        method = "lambda$displayScoreboardSidebar$1(Lnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/network/chat/numbers/NumberFormat;Lnet/minecraft/world/scores/PlayerScoreEntry;)Lnet/minecraft/client/gui/Gui$1DisplayEntry;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void playerlist$decorateSidebarEntry(
        Scoreboard scoreboard,
        NumberFormat numberFormat,
        PlayerScoreEntry entry,
        CallbackInfoReturnable<Object> cir
    ) {
        Object sidebarEntry = cir.getReturnValue();
        if (sidebarEntry == null) {
            return;
        }

        if (!(sidebarEntry instanceof InGameHudSidebarEntryAccessor access)) {
            return;
        }

        Component currentName = access.playerlist$getName();
        if (currentName == null) {
            return;
        }

        Component styledName = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(currentName);
        if (styledName == currentName) {
            return;
        }

        access.playerlist$setName(styledName);
    }

    @ModifyArg(
        method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
        ),
        index = 1
    )
    private Component playerlist$decorateRenderedSidebarText(Component text) {
        if (text == null) {
            return text;
        }

        Component styled = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(text);
        return styled != text ? styled : text;
    }
}
