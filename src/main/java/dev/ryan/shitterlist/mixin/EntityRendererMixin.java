package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void throwerlist$styleLabelState(T entity, S state, float tickDelta, CallbackInfo ci) {
        Text current = state.displayName;
        if (current == null || !NameStyler.INSTANCE.containsStyledTargetName(current.getString())) {
            return;
        }

        state.displayName = NameStyler.INSTANCE.applyNameplateDecorations(current);
    }
}
