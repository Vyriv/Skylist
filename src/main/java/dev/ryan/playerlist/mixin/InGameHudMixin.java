package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(
        method = "method_55439(Lnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/number/NumberFormat;Lnet/minecraft/scoreboard/ScoreboardEntry;)Lnet/minecraft/client/gui/hud/InGameHud$SidebarEntry;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void playerlist$decorateSidebarEntry(
        Scoreboard scoreboard,
        NumberFormat numberFormat,
        ScoreboardEntry entry,
        CallbackInfoReturnable<Object> cir
    ) {
        Object sidebarEntry = cir.getReturnValue();
        if (sidebarEntry == null) {
            return;
        }

        if (!(sidebarEntry instanceof InGameHudSidebarEntryAccessor access)) {
            return;
        }

        Text currentName = access.playerlist$getName();
        if (currentName == null) {
            return;
        }

        Text styledName = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(currentName);
        if (styledName == currentName) {
            return;
        }

        cir.setReturnValue(
            InGameHudSidebarEntryInvoker.playerlist$create(
                styledName,
                access.playerlist$getScore(),
                access.playerlist$getScoreWidth()
            )
        );
    }

    @ModifyArg(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V"
        ),
        index = 1
    )
    private Text playerlist$decorateRenderedSidebarText(Text text) {
        if (text == null) {
            return text;
        }

        Text styled = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(text);
        return styled != text ? styled : text;
    }
}
