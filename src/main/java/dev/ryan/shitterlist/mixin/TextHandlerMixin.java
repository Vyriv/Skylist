package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.font.TextHandler;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextHandler.class)
public abstract class TextHandlerMixin {
    @ModifyVariable(
        method = "getWidth(Ljava/lang/String;)F",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateMeasuredString(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "getWidth(Lnet/minecraft/text/StringVisitable;)F",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateMeasuredVisitable(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }

    @ModifyVariable(
        method = "getWidth(Lnet/minecraft/text/OrderedText;)F",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText throwerlist$decorateMeasuredOrderedText(OrderedText text) {
        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }

    @ModifyVariable(
        method = "getTrimmedLength(Ljava/lang/String;ILnet/minecraft/text/Style;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateTrimmedLengthInput(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "trimToWidth(Ljava/lang/String;ILnet/minecraft/text/Style;)Ljava/lang/String;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateTrimmedString(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "trimToWidthBackwards(Ljava/lang/String;ILnet/minecraft/text/Style;)Ljava/lang/String;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateBackwardsTrimmedString(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "trimToWidth(Lnet/minecraft/text/StringVisitable;ILnet/minecraft/text/Style;)Lnet/minecraft/text/StringVisitable;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateTrimmedVisitable(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }

    @ModifyVariable(
        method = "getEndingIndex(Ljava/lang/String;ILnet/minecraft/text/Style;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateEndingIndexInput(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "wrapLines(Ljava/lang/String;ILnet/minecraft/text/Style;ZLnet/minecraft/client/font/TextHandler$LineWrappingConsumer;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateWrappedString(String text) {
        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "wrapLines(Lnet/minecraft/text/StringVisitable;ILnet/minecraft/text/Style;Ljava/util/function/BiConsumer;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private StringVisitable throwerlist$decorateWrappedVisitable(StringVisitable text) {
        return NameStyler.INSTANCE.applyGradientToVisitable(text);
    }
}
