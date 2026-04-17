package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.CustomScaleState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements CustomScaleState {
    @Unique
    private float throwerlist$customScaleX = 1.0f;

    @Unique
    private float throwerlist$customScaleY = 1.0f;

    @Unique
    private float throwerlist$customScaleZ = 1.0f;

    @Override
    public void throwerlist$setCustomScale(float scaleX, float scaleY, float scaleZ) {
        this.throwerlist$customScaleX = scaleX;
        this.throwerlist$customScaleY = scaleY;
        this.throwerlist$customScaleZ = scaleZ;
    }

    @Override
    public float throwerlist$getCustomScaleX() {
        return this.throwerlist$customScaleX;
    }

    @Override
    public float throwerlist$getCustomScaleY() {
        return this.throwerlist$customScaleY;
    }

    @Override
    public float throwerlist$getCustomScaleZ() {
        return this.throwerlist$customScaleZ;
    }

    @Override
    public void throwerlist$clearCustomScale() {
        this.throwerlist$customScaleX = 1.0f;
        this.throwerlist$customScaleY = 1.0f;
        this.throwerlist$customScaleZ = 1.0f;
    }
}
