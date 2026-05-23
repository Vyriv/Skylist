package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextGuiElementRenderState.class)
public abstract class TextGuiElementRenderStateMixin {
    @Unique
    private OrderedText playerlist$sourceOrderedText;

    @Unique
    private OrderedText playerlist$styledOrderedText;

    @Unique
    private boolean playerlist$animatedOrderedText;

    @Unique
    private long playerlist$styledFrameIndex = Long.MIN_VALUE;

    @Shadow @Final @Mutable
    private OrderedText orderedText;

    @Shadow
    private TextRenderer.GlyphDrawable preparation;

    @Inject(
        method = "prepare()Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
        at = @At("HEAD")
    )
    private void playerlist$decorateQueuedHudText(CallbackInfoReturnable<Object> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.orderedText == null ||
            !NameStyler.INSTANCE.hasGradientStyles() ||
            client == null ||
            client.world == null ||
            client.player == null) {
            return;
        }

        OrderedText current = this.orderedText;
        if (current != this.playerlist$styledOrderedText && current != this.playerlist$sourceOrderedText) {
            this.playerlist$sourceOrderedText = current;
            this.playerlist$styledOrderedText = null;
            this.playerlist$styledFrameIndex = Long.MIN_VALUE;
            this.playerlist$animatedOrderedText = NameStyler.INSTANCE.hasAnimatedGradientInOrderedText(current);
        }

        long frameIndex = this.playerlist$animatedOrderedText
            ? NameStyler.INSTANCE.currentOrderedTextAnimationFrameIndex()
            : Long.MIN_VALUE;
        if (this.playerlist$styledOrderedText != null &&
            (!this.playerlist$animatedOrderedText || frameIndex == this.playerlist$styledFrameIndex)) {
            NameStyler.INSTANCE.recordHudOrderedTextCacheHit();
            if (this.orderedText != this.playerlist$styledOrderedText) {
                this.preparation = null;
                this.orderedText = this.playerlist$styledOrderedText;
            }
            return;
        }

        NameStyler.INSTANCE.recordHudOrderedTextCacheMiss();
        this.preparation = null;
        this.playerlist$styledOrderedText = NameStyler.INSTANCE.applyGradientToOrderedText(this.playerlist$sourceOrderedText);
        this.playerlist$styledFrameIndex = frameIndex;
        this.orderedText = this.playerlist$styledOrderedText;
        NameStyler.INSTANCE.debugRenderReceipt(
            "gui-text-state",
            "TextGuiElementRenderState.prepare",
            null,
            null,
            System.identityHashCode(this.playerlist$styledOrderedText),
            false
        );
    }
}
