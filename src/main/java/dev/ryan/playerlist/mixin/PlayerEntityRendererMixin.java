package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.CustomScaleState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("RETURN"), require = 0)
    private void playerlist$applyAxisScale(AvatarRenderState state, PoseStack matrices, CallbackInfo ci) {
        CustomScaleState customScaleState = (CustomScaleState) state;
        float baseScale = state.scale;
        if (baseScale == 0.0f) {
            return;
        }

        float scaleX = customScaleState.playerlist$getCustomScaleX();
        float scaleY = customScaleState.playerlist$getCustomScaleY();
        float scaleZ = customScaleState.playerlist$getCustomScaleZ();
        if (scaleX == baseScale && scaleY == baseScale && scaleZ == baseScale) {
            return;
        }

        matrices.scale(scaleX / baseScale, scaleY / baseScale, scaleZ / baseScale);
    }
}
