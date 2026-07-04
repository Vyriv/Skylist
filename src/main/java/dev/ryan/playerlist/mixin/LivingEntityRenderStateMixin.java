package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.CustomScaleState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements CustomScaleState {
    @Unique
    private float playerlist$customScaleX = 1.0f;

    @Unique
    private float playerlist$customScaleY = 1.0f;

    @Unique
    private float playerlist$customScaleZ = 1.0f;

    @Override
    public void playerlist$setCustomScale(float scaleX, float scaleY, float scaleZ) {
        this.playerlist$customScaleX = scaleX;
        this.playerlist$customScaleY = scaleY;
        this.playerlist$customScaleZ = scaleZ;
    }

    @Override
    public float playerlist$getCustomScaleX() {
        return this.playerlist$customScaleX;
    }

    @Override
    public float playerlist$getCustomScaleY() {
        return this.playerlist$customScaleY;
    }

    @Override
    public float playerlist$getCustomScaleZ() {
        return this.playerlist$customScaleZ;
    }

    @Override
    public void playerlist$clearCustomScale() {
        this.playerlist$customScaleX = 1.0f;
        this.playerlist$customScaleY = 1.0f;
        this.playerlist$customScaleZ = 1.0f;
    }
}
