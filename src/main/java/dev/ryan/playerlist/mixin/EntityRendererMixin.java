package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.SkylistPresenceManager;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("RETURN"))
    private void playerlist$styleLabelState(T entity, S state, float tickDelta, CallbackInfo ci) {
        Component current = state.nameTag;
        if (current == null) {
            return;
        }
        if (NameStyler.INSTANCE.containsAnimatedStyledTargetName(current.getString())) {
            NameStyler.INSTANCE.debugRenderReceipt(
                "entity-render-state",
                "EntityRenderer.extractRenderState",
                current.getString(),
                current.getString(),
                System.identityHashCode(current),
                true
            );
            return;
        }

        Component styled = NameStyler.INSTANCE.applyNameplateDecorations(current);
        if (entity instanceof Player player) {
            styled = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, player.getGameProfile());
        }
        NameStyler.INSTANCE.debugRenderReceipt(
            "entity-render-state",
            "EntityRenderer.extractRenderState",
            current.getString(),
            styled.getString(),
            System.identityHashCode(styled),
            styled == current
        );
        if (styled != current) {
            state.nameTag = styled;
        }
    }
}
