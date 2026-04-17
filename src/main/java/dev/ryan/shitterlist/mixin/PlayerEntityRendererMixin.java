package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.CustomScaleState;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "scale(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("RETURN"), require = 0)
    private void throwerlist$applyAxisScale(PlayerEntityRenderState state, MatrixStack matrices, CallbackInfo ci) {
        CustomScaleState customScaleState = (CustomScaleState) state;
        float baseScale = state.baseScale;
        if (baseScale == 0.0f) {
            return;
        }

        float scaleX = customScaleState.throwerlist$getCustomScaleX();
        float scaleY = customScaleState.throwerlist$getCustomScaleY();
        float scaleZ = customScaleState.throwerlist$getCustomScaleZ();
        if (scaleX == baseScale && scaleY == baseScale && scaleZ == baseScale) {
            return;
        }

        matrices.scale(scaleX / baseScale, scaleY / baseScale, scaleZ / baseScale);
    }
}
