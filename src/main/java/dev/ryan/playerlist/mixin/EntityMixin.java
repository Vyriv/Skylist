package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void playerlist$styleName(CallbackInfoReturnable<Component> cir) {
        Component styled = NameStyler.INSTANCE.styleEntityName(cir.getReturnValue());
        if (styled != cir.getReturnValue()) {
            cir.setReturnValue(styled);
        }
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void playerlist$styleDisplayName(CallbackInfoReturnable<Component> cir) {
        Component current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (NameStyler.INSTANCE.containsAnimatedStyledTargetName(current.getString())) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyNameplateDecorations(current);
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }
}
