package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    private static boolean throwerlist$shouldDecorateRenderedText() {
        if (!NameStyler.INSTANCE.hasGradientStyles()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        return client != null &&
            client.world != null &&
            client.player != null &&
            client.currentScreen == null;
    }

    @ModifyVariable(
        method = "draw(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String throwerlist$decorateStringDraw(String text) {
        if (!throwerlist$shouldDecorateRenderedText()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToString(text);
    }

    @ModifyVariable(
        method = "draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text throwerlist$decorateDrawnText(Text text) {
        if (text == null || !throwerlist$shouldDecorateRenderedText()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToName(text);
    }

    @ModifyVariable(
        method = "draw(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText throwerlist$decorateDrawnOrderedText(OrderedText text) {
        if (!throwerlist$shouldDecorateRenderedText()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }

    @ModifyVariable(
        method = "drawWithOutline(Lnet/minecraft/text/OrderedText;FFIILorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText throwerlist$decorateOutlinedText(OrderedText text) {
        if (!throwerlist$shouldDecorateRenderedText()) {
            return text;
        }

        return NameStyler.INSTANCE.applyGradientToOrderedText(text);
    }
}
