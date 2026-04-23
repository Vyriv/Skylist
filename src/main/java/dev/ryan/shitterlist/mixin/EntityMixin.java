package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void throwerlist$styleName(CallbackInfoReturnable<Text> cir) {
        Text styled = NameStyler.INSTANCE.styleEntityName(cir.getReturnValue());
        if (styled != cir.getReturnValue()) {
            cir.setReturnValue(styled);
        }
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void throwerlist$styleDisplayName(CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null) {
            return;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDecorations(current);
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }
}
