package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.SkylistPresenceManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void playerlist$styleName(CallbackInfoReturnable<Text> cir) {
        Text styled = NameStyler.INSTANCE.styleEntityName(cir.getReturnValue());
        if (styled != cir.getReturnValue()) {
            cir.setReturnValue(styled);
        }
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void playerlist$styleDisplayName(CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        if (NameStyler.INSTANCE.containsAnimatedStyledTargetName(current.getString())) {
            return;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDecorations(current);
        if ((Object) this instanceof PlayerEntity player) {
            styled = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, player.getGameProfile());
        }
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }
}
