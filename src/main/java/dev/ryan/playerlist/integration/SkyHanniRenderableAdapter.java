package dev.ryan.playerlist.integration;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.text.Text;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class SkyHanniRenderableAdapter {
    private static final String SKYHANNI_PRIMITIVES_PACKAGE = "at.hannibal2.skyhanni.utils.renderables.primitives.";
    private static final String STRING_RENDERABLE_CLASS = "at.hannibal2.skyhanni.utils.renderables.primitives.StringRenderable";
    private static final String TEXT_RENDERABLE_CLASS = "at.hannibal2.skyhanni.utils.renderables.primitives.TextRenderable";

    private SkyHanniRenderableAdapter() {
    }

    public static Object createStyledRenderable(Object renderable) {
        try {
            if (renderable == null) {
                return null;
            }

            Class<?> renderableClass = renderable.getClass();
            if (!isSupportedSkyHanniRenderable(renderableClass)) {
                return null;
            }

            // SkyHanni is an optional soft dependency that is not present on the normal
            // compile classpath. Reflection is isolated here so the rest of Skylist stays
            // direct and readable. This helper only calls public getter methods and then
            // reconstructs a public renderable instance with styled text.
            Method getTextMethod = renderableClass.getMethod("getText");
            Object rawText = getTextMethod.invoke(renderable);
            if (!(rawText instanceof String) && !(rawText instanceof Text)) {
                return null;
            }

            Text styledText = styleRenderableText(rawText);
            if (styledText == null) {
                return null;
            }

            Method getScaleMethod = renderableClass.getMethod("getScale");
            Method getColorMethod = renderableClass.getMethod("getColor");
            Method getHorizontalAlignMethod = renderableClass.getMethod("getHorizontalAlign");
            Method getVerticalAlignMethod = renderableClass.getMethod("getVerticalAlign");

            double scale = (double) getScaleMethod.invoke(renderable);
            Color color = (Color) getColorMethod.invoke(renderable);
            Object horizontalAlign = getHorizontalAlignMethod.invoke(renderable);
            Object verticalAlign = getVerticalAlignMethod.invoke(renderable);

            return constructTextRenderable(styledText, scale, color, horizontalAlign, verticalAlign);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Text styleRenderableText(Object rawText) {
        if (rawText instanceof String text) {
            String styledString = NameStyler.INSTANCE.applyScoreboardDisplayDecorationsToString(text);
            if (styledString == text) {
                return null;
            }
            return NameStyler.INSTANCE.applyScoreboardDisplayDecorations(styledString);
        }

        Text text = (Text) rawText;
        Text styledText = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(text);
        return styledText == text ? null : styledText;
    }

    private static Object constructTextRenderable(
            Text styledText,
            double scale,
            Color color,
            Object horizontalAlign,
            Object verticalAlign
    ) throws ReflectiveOperationException {
        Class<?> textRenderableClass = Class.forName(TEXT_RENDERABLE_CLASS);
        for (Constructor<?> constructor : textRenderableClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != 5) {
                continue;
            }
            if (!parameterTypes[0].isInstance(styledText)) {
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
            return constructor.newInstance(styledText, scale, color, horizontalAlign, verticalAlign);
        }
        return null;
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
