package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @ModifyVariable(
        method = "draw(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateStringDraw(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "getWidth(Ljava/lang/String;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateMeasuredString(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text throwerlist$decorateDrawnText(Text text) {
        if (text == null) {
            return null;
        }

        return NameStyler.INSTANCE.applyGradientToName(text);
    }

    @ModifyVariable(
        method = "draw(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText throwerlist$decorateDrawnOrderedText(OrderedText text) {
        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }

    @ModifyVariable(
        method = "drawWithOutline(Lnet/minecraft/text/OrderedText;FFIILorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText throwerlist$decorateOutlinedText(OrderedText text) {
        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }

    @ModifyVariable(
        method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText throwerlist$decorateMeasuredOrderedText(OrderedText text) {
        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }

    @ModifyVariable(
        method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateMeasuredVisitable(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }

    @ModifyVariable(
        method = "trimToWidth(Lnet/minecraft/text/StringVisitable;I)Lnet/minecraft/text/StringVisitable;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateTrimmedVisitable(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }

    @ModifyVariable(
        method = "getWrappedLinesHeight(Lnet/minecraft/text/StringVisitable;I)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateWrappedLineHeightInput(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }

    @ModifyVariable(
        method = "wrapLines(Lnet/minecraft/text/StringVisitable;I)Ljava/util/List;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateWrappedLinesInput(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }

    @ModifyVariable(
        method = "wrapLinesWithoutLanguage(Lnet/minecraft/text/StringVisitable;I)Ljava/util/List;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateLanguageFreeWrappedLinesInput(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }
}
