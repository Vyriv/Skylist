package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    private static final ThreadLocal<Integer> playerlist$decorationDepth = ThreadLocal.withInitial(() -> 0);

    private static boolean playerlist$shouldDecorateRenderedText() {
        if (!NameStyler.INSTANCE.hasGradientStyles()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        return client != null &&
            client.world != null &&
            client.player != null &&
            playerlist$decorationDepth.get() == 0;
    }

    private static boolean playerlist$shouldDecorateMeasuredText() {
        if (!NameStyler.INSTANCE.hasGradientStyles() && !NameStyler.INSTANCE.hasChatHeaderStyles()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        return client != null &&
            client.world != null &&
            client.player != null &&
            playerlist$decorationDepth.get() == 0;
    }

    private static <T> T playerlist$decorateSafely(java.util.function.Supplier<T> action) {
        playerlist$decorationDepth.set(playerlist$decorationDepth.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = playerlist$decorationDepth.get() - 1;
            if (depth <= 0) {
                playerlist$decorationDepth.remove();
            } else {
                playerlist$decorationDepth.set(depth);
            }
        }
    }

    @ModifyVariable(
        method = "draw(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String playerlist$decorateStringDraw(String text) {
        if (!playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        return playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToString(text));
    }

    @ModifyVariable(
        method = "draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text playerlist$decorateDrawnText(Text text) {
        if (text == null || !playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        Text styled = playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToName(text));
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
    private OrderedText playerlist$decorateDrawnOrderedText(OrderedText text) {
        if (!playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        OrderedText styled = playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToOrderedText(text));
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
    private OrderedText playerlist$decorateOutlinedText(OrderedText text) {
        if (!playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        OrderedText styled = playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToOrderedText(text));
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

    @ModifyVariable(
        method = "getWidth(Ljava/lang/String;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String playerlist$decorateMeasuredString(String text) {
        if (!playerlist$shouldDecorateMeasuredText()) {
            return text;
        }

        return playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToString(text));
    }

    @ModifyVariable(
        method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private net.minecraft.text.StringVisitable playerlist$decorateMeasuredVisitable(net.minecraft.text.StringVisitable text) {
        if (text == null || !playerlist$shouldDecorateMeasuredText()) {
            return text;
        }

        return playerlist$decorateSafely(() -> {
            if (text instanceof Text styledText) {
                if (NameStyler.INSTANCE.hasChatHeaderStyles()) {
                    styledText = NameStyler.INSTANCE.applyGradientToChatHeader(styledText);
                }
                if (NameStyler.INSTANCE.hasGradientStyles()) {
                    styledText = NameStyler.INSTANCE.applyGradientToName(styledText);
                }
                return styledText;
            }

            return NameStyler.INSTANCE.applyGradientToVisitable(text);
        });
    }

    @ModifyVariable(
        method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private OrderedText playerlist$decorateMeasuredOrderedText(OrderedText text) {
        if (text == null || !playerlist$shouldDecorateMeasuredText()) {
            return text;
        }

        return playerlist$decorateSafely(() -> {
            OrderedText styled = text;
            if (NameStyler.INSTANCE.hasChatHeaderStyles()) {
                styled = NameStyler.INSTANCE.applyChatHeaderToOrderedText(styled);
            }
            if (NameStyler.INSTANCE.hasGradientStyles()) {
                styled = NameStyler.INSTANCE.applyGradientToOrderedText(styled);
            }
            return styled;
        });
    }
}
