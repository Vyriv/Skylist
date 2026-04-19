package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(
        method = "method_55439(Lnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/number/NumberFormat;Lnet/minecraft/scoreboard/ScoreboardEntry;)Lnet/minecraft/client/gui/hud/InGameHud$SidebarEntry;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void throwerlist$decorateSidebarEntry(
        Scoreboard scoreboard,
        NumberFormat numberFormat,
        ScoreboardEntry entry,
        CallbackInfoReturnable<Object> cir
    ) {
        Object sidebarEntry = cir.getReturnValue();
        if (sidebarEntry == null) {
            return;
        }

        try {
            Method nameMethod = sidebarEntry.getClass().getMethod("name");
            Text currentName = (Text) nameMethod.invoke(sidebarEntry);
            if (currentName == null || !NameStyler.INSTANCE.containsStyledScoreboardTargetName(currentName.getString())) {
                return;
            }

            Text styledName = NameStyler.INSTANCE.applyScoreboardDecorations(currentName);
            Method scoreMethod = sidebarEntry.getClass().getMethod("score");
            Method scoreWidthMethod = sidebarEntry.getClass().getMethod("scoreWidth");
            Text score = (Text) scoreMethod.invoke(sidebarEntry);
            int scoreWidth = (int) scoreWidthMethod.invoke(sidebarEntry);

            Constructor<?> constructor = sidebarEntry.getClass().getDeclaredConstructor(Text.class, Text.class, int.class);
            constructor.setAccessible(true);
            cir.setReturnValue(constructor.newInstance(styledName, score, scoreWidth));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @ModifyArg(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V"
        ),
        index = 1
    )
    private Text throwerlist$decorateRenderedSidebarText(Text text) {
        if (text == null || !NameStyler.INSTANCE.containsStyledScoreboardTargetName(text.getString())) {
            return text;
        }

        return NameStyler.INSTANCE.applyScoreboardDecorations(text);
    }
}
