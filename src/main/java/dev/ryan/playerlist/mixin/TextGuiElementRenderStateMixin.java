package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiTextRenderState.class)
public abstract class TextGuiElementRenderStateMixin {
    @Unique
    private FormattedCharSequence playerlist$sourceOrderedText;

    @Unique
    private FormattedCharSequence playerlist$styledOrderedText;

    @Unique
    private boolean playerlist$animatedOrderedText;

    @Unique
    private long playerlist$styledFrameIndex = Long.MIN_VALUE;

    @Shadow @Final @Mutable
    public FormattedCharSequence text;

    @Shadow
    private Font.PreparedText preparedText;

    @Inject(
        method = "ensurePrepared()Lnet/minecraft/client/gui/Font$PreparedText;",
        at = @At("HEAD")
    )
    private void playerlist$decorateQueuedHudText(CallbackInfoReturnable<Font.PreparedText> cir) {
        Minecraft client = Minecraft.getInstance();
        if (this.text == null ||
            !NameStyler.INSTANCE.hasGradientStyles() ||
            client == null ||
            client.level == null ||
            client.player == null) {
            return;
        }

        FormattedCharSequence current = this.text;
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
            if (this.text != this.playerlist$styledOrderedText) {
                this.preparedText = null;
                this.text = this.playerlist$styledOrderedText;
            }
            return;
        }

        NameStyler.INSTANCE.recordHudOrderedTextCacheMiss();
        this.preparedText = null;
        this.playerlist$styledOrderedText = NameStyler.INSTANCE.applyGradientToOrderedText(this.playerlist$sourceOrderedText);
        this.playerlist$styledFrameIndex = frameIndex;
        this.text = this.playerlist$styledOrderedText;
        NameStyler.INSTANCE.debugRenderReceipt(
            "gui-text-state",
            "GuiTextRenderState.ensurePrepared",
            null,
            null,
            System.identityHashCode(this.playerlist$styledOrderedText),
            false
        );
    }
}
