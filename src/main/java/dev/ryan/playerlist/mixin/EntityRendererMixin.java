package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.SkylistPresenceManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void playerlist$styleLabelState(T entity, S state, float tickDelta, CallbackInfo ci) {
        Text current = state.displayName;
        if (current == null) {
            return;
        }
        if (NameStyler.INSTANCE.containsAnimatedStyledTargetName(current.getString())) {
            NameStyler.INSTANCE.debugRenderReceipt(
                "entity-render-state",
                "EntityRenderer.updateRenderState",
                current.getString(),
                current.getString(),
                System.identityHashCode(current),
                true
            );
            return;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDecorations(current);
        if (entity instanceof PlayerEntity player) {
            styled = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, player.getGameProfile());
        }
        NameStyler.INSTANCE.debugRenderReceipt(
            "entity-render-state",
            "EntityRenderer.updateRenderState",
            current.getString(),
            styled.getString(),
            System.identityHashCode(styled),
            styled == current
        );
        if (styled != current) {
            state.displayName = styled;
        }
    }
}
