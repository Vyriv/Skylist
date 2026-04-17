package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard", remap = false)
public abstract class SkyHanniCustomScoreboardMixin {
    @Redirect(
        method = "createRenderable(Ljava/util/List;)Lat/hannibal2/skyhanni/utils/renderables/container/VerticalContainerRenderable;",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Collection;add(Ljava/lang/Object;)Z"
        ),
        remap = false
    )
    private boolean throwerlist$replaceSkyHanniRenderable(Collection<Object> renderables, Object renderable) {
        Object replacement = createStyledRenderable(renderable);
        return renderables.add(replacement != null ? replacement : renderable);
    }

    private static Object createStyledRenderable(Object renderable) {
        try {
            if (renderable == null || !renderable.getClass().getName().equals("at.hannibal2.skyhanni.utils.renderables.primitives.StringRenderable")) {
                return null;
            }

            Method getText = renderable.getClass().getMethod("getText");
            String text = (String) getText.invoke(renderable);
            if (!NameStyler.INSTANCE.containsStyledTargetName(text)) {
                return null;
            }

            Method getScale = renderable.getClass().getMethod("getScale");
            Method getColor = renderable.getClass().getMethod("getColor");
            Method getHorizontalAlign = renderable.getClass().getMethod("getHorizontalAlign");
            Method getVerticalAlign = renderable.getClass().getMethod("getVerticalAlign");

            double scale = (double) getScale.invoke(renderable);
            Color color = (Color) getColor.invoke(renderable);
            Object horizontalAlign = getHorizontalAlign.invoke(renderable);
            Object verticalAlign = getVerticalAlign.invoke(renderable);

            Text styled = NameStyler.INSTANCE.applyNameplateDecorations(text);

            Class<?> horizontalClass = horizontalAlign.getClass();
            Class<?> verticalClass = verticalAlign.getClass();
            Class<?> textRenderableClass = Class.forName("at.hannibal2.skyhanni.utils.renderables.primitives.TextRenderable");
            Constructor<?> constructor = textRenderableClass.getConstructor(Text.class, double.class, Color.class, horizontalClass, verticalClass);
            return constructor.newInstance(styled, scale, color, horizontalAlign, verticalAlign);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
