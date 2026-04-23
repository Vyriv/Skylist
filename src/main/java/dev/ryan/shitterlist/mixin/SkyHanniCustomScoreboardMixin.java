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
    private static final String SKYHANNI_PRIMITIVES_PACKAGE = "at.hannibal2.skyhanni.utils.renderables.primitives.";
    private static final String STRING_RENDERABLE_CLASS = "at.hannibal2.skyhanni.utils.renderables.primitives.StringRenderable";
    private static final String TEXT_RENDERABLE_CLASS = "at.hannibal2.skyhanni.utils.renderables.primitives.TextRenderable";

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
            if (renderable == null) {
                return null;
            }

            if (!isSupportedSkyHanniRenderable(renderable.getClass())) {
                return null;
            }

            Method getText = renderable.getClass().getMethod("getText");
            Object rawText = getText.invoke(renderable);
            if (!(rawText instanceof String) && !(rawText instanceof Text)) {
                return null;
            }

            Text styled;
            if (rawText instanceof String text) {
                String styledString = NameStyler.INSTANCE.applyScoreboardDecorationsToString(text);
                if (styledString == text) {
                    return null;
                }
                styled = NameStyler.INSTANCE.applyScoreboardDecorations(styledString);
            } else {
                Text text = (Text) rawText;
                styled = NameStyler.INSTANCE.applyScoreboardDecorations(text);
                if (styled == text) {
                    return null;
                }
            }

            Method getScale = renderable.getClass().getMethod("getScale");
            Method getColor = renderable.getClass().getMethod("getColor");
            Method getHorizontalAlign = renderable.getClass().getMethod("getHorizontalAlign");
            Method getVerticalAlign = renderable.getClass().getMethod("getVerticalAlign");

            double scale = (double) getScale.invoke(renderable);
            Color color = (Color) getColor.invoke(renderable);
            Object horizontalAlign = getHorizontalAlign.invoke(renderable);
            Object verticalAlign = getVerticalAlign.invoke(renderable);

            Class<?> textRenderableClass = Class.forName(TEXT_RENDERABLE_CLASS);
            for (Constructor<?> constructor : textRenderableClass.getConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != 5) {
                    continue;
                }
                if (!parameterTypes[0].isInstance(styled)) {
                    continue;
                }
                if (!(parameterTypes[1] == double.class || parameterTypes[1] == Double.class)) {
                    continue;
                }
                if (!parameterTypes[2].isInstance(color)) {
                    continue;
                }
                if (!parameterTypes[3].isInstance(horizontalAlign) || !parameterTypes[4].isInstance(verticalAlign)) {
                    continue;
                }
                return constructor.newInstance(styled, scale, color, horizontalAlign, verticalAlign);
            }
            return null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isSupportedSkyHanniRenderable(Class<?> renderableClass) {
        String className = renderableClass.getName();
        if (STRING_RENDERABLE_CLASS.equals(className) || TEXT_RENDERABLE_CLASS.equals(className)) {
            return true;
        }
        if (!className.startsWith(SKYHANNI_PRIMITIVES_PACKAGE)) {
            return false;
        }

        try {
            renderableClass.getMethod("getText");
            renderableClass.getMethod("getScale");
            renderableClass.getMethod("getColor");
            renderableClass.getMethod("getHorizontalAlign");
            renderableClass.getMethod("getVerticalAlign");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
