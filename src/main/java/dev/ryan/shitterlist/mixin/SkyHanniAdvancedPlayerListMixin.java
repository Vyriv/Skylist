package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import dev.ryan.throwerlist.SkylistPresenceManager;
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
        method = "createTabLine(Ljava/lang/String;Lat/hannibal2/skyhanni/features/misc/compacttablist/TabStringType;)Lat/hannibal2/skyhanni/features/misc/compacttablist/TabLine;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void throwerlist$decorateCompactTabLine(
        String component,
        @Coerce Object type,
        CallbackInfoReturnable<Object> cir
    ) {
        Object line = cir.getReturnValue();
        if (line == null) {
            return;
        }

        try {
            String currentComponent = (String) line.getClass().getMethod("getText").invoke(line);
            String currentCustomName = (String) line.getClass().getMethod("getCustomName").invoke(line);

            String renderedCustomName = styleIfNeeded(currentCustomName);

            if (renderedCustomName == null || renderedCustomName.equals(currentCustomName)) {
                return;
            }

            Object replacement = line.getClass()
                .getConstructor(String.class, type.getClass(), String.class)
                .newInstance(currentComponent, type, renderedCustomName);
            cir.setReturnValue(replacement);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static String styleIfNeeded(String text) {
        if (text == null) {
            return null;
        }

        String styled = NameStyler.INSTANCE.applyNameplateDisplayDecorationsToString(text);
        return SkylistPresenceManager.INSTANCE.applyIdentifierToString(styled, text);
    }
}
