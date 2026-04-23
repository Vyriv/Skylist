package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import dev.ryan.throwerlist.SidebarEntryAccess;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    private static final ConcurrentMap<Class<?>, Optional<SidebarEntryAccess>> throwerlist$sidebarEntryAccessors = new ConcurrentHashMap<>();

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
            SidebarEntryAccess access = throwerlist$sidebarEntryAccess(sidebarEntry);
            if (access == null) {
                return;
            }

            Text currentName = (Text) access.nameMethod().invoke(sidebarEntry);
            if (currentName == null) {
                return;
            }

            Text styledName = NameStyler.INSTANCE.applyScoreboardDecorations(currentName);
            if (styledName == currentName) {
                return;
            }
            Text score = (Text) access.scoreMethod().invoke(sidebarEntry);
            int scoreWidth = (int) access.scoreWidthMethod().invoke(sidebarEntry);

            cir.setReturnValue(access.constructor().newInstance(styledName, score, scoreWidth));
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
        if (text == null) {
            return text;
        }

        Text styled = NameStyler.INSTANCE.applyScoreboardDecorations(text);
        return styled != text ? styled : text;
    }

    private static SidebarEntryAccess throwerlist$sidebarEntryAccess(Object sidebarEntry) {
        return throwerlist$sidebarEntryAccessors.computeIfAbsent(sidebarEntry.getClass(), InGameHudMixin::throwerlist$createSidebarEntryAccess)
            .orElse(null);
    }

    private static Optional<SidebarEntryAccess> throwerlist$createSidebarEntryAccess(Class<?> sidebarEntryClass) {
        try {
            Method nameMethod = sidebarEntryClass.getMethod("name");
            Method scoreMethod = sidebarEntryClass.getMethod("score");
            Method scoreWidthMethod = sidebarEntryClass.getMethod("scoreWidth");
            Constructor<?> constructor = sidebarEntryClass.getDeclaredConstructor(Text.class, Text.class, int.class);
            constructor.setAccessible(true);
            return Optional.of(new SidebarEntryAccess(nameMethod, scoreMethod, scoreWidthMethod, constructor));
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }
}
