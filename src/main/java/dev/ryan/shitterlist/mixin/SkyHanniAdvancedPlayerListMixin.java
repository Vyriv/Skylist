package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.AdvancedPlayerList", remap = false)
public abstract class SkyHanniAdvancedPlayerListMixin {
    @Inject(
        method = "createTabLine(Lnet/minecraft/class_2561;Lat/hannibal2/skyhanni/features/misc/compacttablist/TabStringType;)Lat/hannibal2/skyhanni/features/misc/compacttablist/TabLine;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void throwerlist$decorateCompactTabLine(
        Text component,
        @Coerce Object type,
        CallbackInfoReturnable<Object> cir
    ) {
        Object line = cir.getReturnValue();
        if (line == null) {
            return;
        }

        try {
            Text currentComponent = (Text) line.getClass().getMethod("getComponent").invoke(line);
            Text currentCustomName = (Text) line.getClass().getMethod("getCustomName").invoke(line);

            Text styledComponent = styleIfNeeded(currentComponent);
            Text styledCustomName = styleIfNeeded(currentCustomName);

            if (styledComponent == currentComponent && styledCustomName == currentCustomName) {
                return;
            }

            Object replacement = line.getClass()
                .getConstructor(Text.class, type.getClass(), Text.class)
                .newInstance(styledComponent, type, styledCustomName);
            cir.setReturnValue(replacement);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Text styleIfNeeded(Text text) {
        if (text == null || !NameStyler.INSTANCE.containsStyledTargetName(text.getString())) {
            return text;
        }

        return NameStyler.INSTANCE.applyNameplateDecorations(text);
    }
}
