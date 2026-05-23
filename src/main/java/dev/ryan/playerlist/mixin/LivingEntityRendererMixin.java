package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.CustomScaleState;
import dev.ryan.playerlist.ConfigManager;
import dev.ryan.playerlist.PlayerCustomizationRegistry;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState> {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", at = @At("RETURN"), require = 0)
    private void playerlist$applyCustomScale(T entity, S state, float tickDelta, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity)) {
            return;
        }

        CustomScaleState customScaleState = (CustomScaleState) state;
        customScaleState.playerlist$clearCustomScale();
        if (!ConfigManager.INSTANCE.isCustomScalerEnabled()) {
            return;
        }
        if (!PlayerCustomizationRegistry.INSTANCE.hasScaleCustomizations()) {
            return;
        }

        PlayerCustomizationRegistry.PlayerCustomization customization =
            PlayerCustomizationRegistry.INSTANCE.findWithScale(((PlayerEntity) entity).getGameProfile());
        if (customization == null) {
            return;
        }

        float vanillaBaseScale = state.baseScale;
        float uniformScale = customization.getScale() != null ? customization.getScale() : vanillaBaseScale;
        float finalScaleX = customization.getScaleX() != null ? customization.getScaleX() : uniformScale;
        float finalScaleY = customization.getScaleY() != null ? customization.getScaleY() : uniformScale;
        float finalScaleZ = customization.getScaleZ() != null ? customization.getScaleZ() : uniformScale;

        state.baseScale = uniformScale;
        customScaleState.playerlist$setCustomScale(finalScaleX, finalScaleY, finalScaleZ);
    }

    @Inject(method = "scale(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("RETURN"), require = 0)
    private void playerlist$applyAxisScale(S state, MatrixStack matrices, CallbackInfo ci) {
        CustomScaleState customScaleState = (CustomScaleState) state;
        float baseScale = state.baseScale;
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
