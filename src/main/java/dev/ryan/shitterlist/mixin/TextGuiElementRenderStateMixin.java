package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextGuiElementRenderState.class)
public abstract class TextGuiElementRenderStateMixin {
    @Shadow @Final @Mutable
    private OrderedText orderedText;

    @Inject(
        method = "prepare()Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
        at = @At("HEAD")
    )
    private void throwerlist$decorateQueuedHudText(CallbackInfoReturnable<Object> cir) {
        if (this.orderedText == null) {
            return;
        }

        this.orderedText = NameStyler.INSTANCE.applyGradientToOrderedText(this.orderedText);
    }
}
