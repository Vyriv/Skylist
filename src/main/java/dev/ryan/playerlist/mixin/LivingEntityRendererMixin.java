package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.CustomScaleState;
import dev.ryan.playerlist.ConfigManager;
import dev.ryan.playerlist.PlayerCustomizationRegistry;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState> {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("RETURN"), require = 0)
    private void playerlist$applyCustomScale(T entity, S state, float tickDelta, CallbackInfo ci) {
        if (!(entity instanceof Player)) {
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
            PlayerCustomizationRegistry.INSTANCE.findWithScale(((Player) entity).getGameProfile());
        if (customization == null) {
            return;
        }

        float vanillaBaseScale = state.scale;
        float uniformScale = customization.getScale() != null ? customization.getScale() : vanillaBaseScale;
        float finalScaleX = customization.getScaleX() != null ? customization.getScaleX() : uniformScale;
        float finalScaleY = customization.getScaleY() != null ? customization.getScaleY() : uniformScale;
        float finalScaleZ = customization.getScaleZ() != null ? customization.getScaleZ() : uniformScale;

        state.scale = uniformScale;
        customScaleState.playerlist$setCustomScale(finalScaleX, finalScaleY, finalScaleZ);
    }

    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("RETURN"), require = 0)
    private void playerlist$applyAxisScale(S state, PoseStack matrices, CallbackInfo ci) {
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
