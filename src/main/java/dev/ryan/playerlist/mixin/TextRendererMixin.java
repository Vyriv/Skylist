package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public abstract class TextRendererMixin {
    private static final ThreadLocal<Integer> playerlist$decorationDepth = ThreadLocal.withInitial(() -> 0);

    private static boolean playerlist$shouldDecorateRenderedText() {
        if (!NameStyler.INSTANCE.hasGradientStyles()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        return client != null &&
            client.level != null &&
            client.player != null &&
            playerlist$decorationDepth.get() == 0;
    }

    private static boolean playerlist$shouldDecorateMeasuredText() {
        if (!NameStyler.INSTANCE.hasGradientStyles() && !NameStyler.INSTANCE.hasChatHeaderStyles()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        return client != null &&
            client.level != null &&
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

    // Font.drawInBatch*/drawInBatch8xOutline were removed in 26.2 - all Font drawing there goes
    // through Font.prepareText()/prepare8xTextOutline() consumed by RenderState classes instead.
    // On 26.2, on-screen text gradient decoration happens exclusively via
    // TextGuiElementRenderStateMixin (GuiTextRenderState.ensurePrepared() is the sole GUI text
    // entry point there). The 8x-outline path below (world-space text, e.g. entity text/nametags)
    // has no confirmed 26.2 replacement yet - outlined-text gradients are a known gap on 26.2.
    //? if <26.2 {
    @ModifyVariable(
        method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
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
        method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Component playerlist$decorateDrawnText(Component text) {
        if (text == null || !playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        Component styled = playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToName(text));
        NameStyler.INSTANCE.debugRenderReceipt(
            "text-renderer",
            "Font.drawInBatch(Component)",
            text.getString(),
            styled.getString(),
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
    }

    @ModifyVariable(
        method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedCharSequence playerlist$decorateDrawnOrderedText(FormattedCharSequence text) {
        if (!playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        FormattedCharSequence styled = playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToOrderedText(text));
        NameStyler.INSTANCE.debugRenderReceipt(
            "text-renderer",
            "Font.drawInBatch(FormattedCharSequence)",
            null,
            null,
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
    }

    @ModifyVariable(
        method = "drawInBatch8xOutline(Lnet/minecraft/util/FormattedCharSequence;FFIILorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedCharSequence playerlist$decorateOutlinedText(FormattedCharSequence text) {
        if (!playerlist$shouldDecorateRenderedText()) {
            return text;
        }

        FormattedCharSequence styled = playerlist$decorateSafely(() -> NameStyler.INSTANCE.applyGradientToOrderedText(text));
        NameStyler.INSTANCE.debugRenderReceipt(
            "text-renderer",
            "Font.drawInBatch8xOutline(FormattedCharSequence)",
            null,
            null,
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
    }
    //?}

    @ModifyVariable(
        method = "width(Ljava/lang/String;)I",
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
        method = "width(Lnet/minecraft/network/chat/FormattedText;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedText playerlist$decorateMeasuredVisitable(FormattedText text) {
        if (text == null || !playerlist$shouldDecorateMeasuredText()) {
            return text;
        }

        return playerlist$decorateSafely(() -> {
            if (text instanceof Component component) {
                Component styled = component;
                if (NameStyler.INSTANCE.hasChatHeaderStyles()) {
                    styled = NameStyler.INSTANCE.applyGradientToChatHeader(styled);
                }
                if (NameStyler.INSTANCE.hasGradientStyles()) {
                    styled = NameStyler.INSTANCE.applyGradientToName(styled);
                }
                return styled;
            }

            return NameStyler.INSTANCE.applyGradientToVisitable(text);
        });
    }

    @ModifyVariable(
        method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedCharSequence playerlist$decorateMeasuredOrderedText(FormattedCharSequence text) {
        if (text == null || !playerlist$shouldDecorateMeasuredText()) {
            return text;
        }

        return playerlist$decorateSafely(() -> {
            FormattedCharSequence styled = text;
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
