package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
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
    private OrderedText throwerlist$sourceOrderedText;

    @Unique
    private OrderedText throwerlist$styledOrderedText;

    @Unique
    private boolean throwerlist$animatedOrderedText;

    @Unique
    private long throwerlist$styledFrameIndex = Long.MIN_VALUE;

    @Shadow @Final @Mutable
    private OrderedText orderedText;

    @Shadow
    private TextRenderer.GlyphDrawable preparation;

    @Inject(
        method = "prepare()Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
        at = @At("HEAD")
    )
    private void throwerlist$decorateQueuedHudText(CallbackInfoReturnable<Object> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.orderedText == null ||
            !NameStyler.INSTANCE.hasGradientStyles() ||
            client == null ||
            client.world == null ||
            client.player == null) {
            return;
        }

        OrderedText current = this.orderedText;
        if (current != this.throwerlist$styledOrderedText && current != this.throwerlist$sourceOrderedText) {
            this.throwerlist$sourceOrderedText = current;
            this.throwerlist$styledOrderedText = null;
            this.throwerlist$styledFrameIndex = Long.MIN_VALUE;
            this.throwerlist$animatedOrderedText = NameStyler.INSTANCE.hasAnimatedGradientInOrderedText(current);
        }

        long frameIndex = this.throwerlist$animatedOrderedText
            ? NameStyler.INSTANCE.currentOrderedTextAnimationFrameIndex()
            : Long.MIN_VALUE;
        if (this.throwerlist$styledOrderedText != null &&
            (!this.throwerlist$animatedOrderedText || frameIndex == this.throwerlist$styledFrameIndex)) {
            NameStyler.INSTANCE.recordHudOrderedTextCacheHit();
            if (this.orderedText != this.throwerlist$styledOrderedText) {
                this.preparation = null;
                this.orderedText = this.throwerlist$styledOrderedText;
            }
            return;
        }

        NameStyler.INSTANCE.recordHudOrderedTextCacheMiss();
        this.preparation = null;
        this.throwerlist$styledOrderedText = NameStyler.INSTANCE.applyGradientToOrderedText(this.throwerlist$sourceOrderedText);
        this.throwerlist$styledFrameIndex = frameIndex;
        this.orderedText = this.throwerlist$styledOrderedText;
        NameStyler.INSTANCE.debugRenderReceipt(
            "gui-text-state",
            "TextGuiElementRenderState.prepare",
            null,
            null,
            System.identityHashCode(this.throwerlist$styledOrderedText),
            false
        );
    }
}
