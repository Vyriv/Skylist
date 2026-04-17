package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.CustomScaleState;
import dev.ryan.throwerlist.PlayerCustomizationRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState> {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", at = @At("RETURN"), require = 0)
    private void throwerlist$applyCustomScale(T entity, S state, float tickDelta, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity)) {
            return;
        }

        CustomScaleState customScaleState = (CustomScaleState) state;
        customScaleState.throwerlist$clearCustomScale();

        PlayerCustomizationRegistry.PlayerCustomization customization = throwerlist$findCustomization(entity);
        if (customization == null) {
            return;
        }

        float vanillaBaseScale = state.baseScale;
        float uniformScale = customization.getScale() != null ? customization.getScale() : vanillaBaseScale;
        float finalScaleX = customization.getScaleX() != null ? customization.getScaleX() : uniformScale;
        float finalScaleY = customization.getScaleY() != null ? customization.getScaleY() : uniformScale;
        float finalScaleZ = customization.getScaleZ() != null ? customization.getScaleZ() : uniformScale;

        state.baseScale = uniformScale;
        customScaleState.throwerlist$setCustomScale(finalScaleX, finalScaleY, finalScaleZ);
    }

    @Inject(method = "scale(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("RETURN"), require = 0)
    private void throwerlist$applyAxisScale(S state, MatrixStack matrices, CallbackInfo ci) {
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

    private PlayerCustomizationRegistry.PlayerCustomization throwerlist$findCustomization(T entity) {
        String displayName = entity.getName().getString();
        String cleanName = Formatting.strip(displayName);
        PlayerCustomizationRegistry.PlayerCustomization customization = PlayerCustomizationRegistry.INSTANCE.findByName(cleanName);
        if (customization != null) {
            return customization;
        }

        customization = PlayerCustomizationRegistry.INSTANCE.findByName(displayName);
        if (customization != null) {
            return customization;
        }

        PlayerEntity localPlayer = MinecraftClient.getInstance().player;
        if (localPlayer == null) {
            return null;
        }

        String localName = localPlayer.getName().getString();
        if (!displayName.equals(localName) && !displayName.isEmpty()) {
            return null;
        }

        String localCleanName = Formatting.strip(localName);
        PlayerCustomizationRegistry.PlayerCustomization localCustomization = PlayerCustomizationRegistry.INSTANCE.findByName(localCleanName);
        return localCustomization != null ? localCustomization : PlayerCustomizationRegistry.INSTANCE.findByName(localName);
    }
}
