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
    private static final ThreadLocal<Integer> throwerlist$decorationDepth = ThreadLocal.withInitial(() -> 0);

    private static boolean throwerlist$shouldDecorateRenderedText() {
        if (!NameStyler.INSTANCE.hasGradientStyles()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        return client != null &&
            client.world != null &&
            client.player != null &&
            throwerlist$decorationDepth.get() == 0;
    }

    private static <T> T throwerlist$decorateSafely(java.util.function.Supplier<T> action) {
        throwerlist$decorationDepth.set(throwerlist$decorationDepth.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = throwerlist$decorationDepth.get() - 1;
            if (depth <= 0) {
                throwerlist$decorationDepth.remove();
            } else {
                throwerlist$decorationDepth.set(depth);
            }
        }
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

        return throwerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToString(text));
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

        Text styled = throwerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToName(text));
        NameStyler.INSTANCE.debugRenderReceipt(
            "text-renderer",
            "TextRenderer.draw(Text)",
            text.getString(),
            styled.getString(),
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
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

        OrderedText styled = throwerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToOrderedText(text));
        NameStyler.INSTANCE.debugRenderReceipt(
            "text-renderer",
            "TextRenderer.draw(OrderedText)",
            null,
            null,
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
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

        OrderedText styled = throwerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToOrderedText(text));
        NameStyler.INSTANCE.debugRenderReceipt(
            "text-renderer",
            "TextRenderer.drawWithOutline(OrderedText)",
            null,
            null,
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
    }
}
