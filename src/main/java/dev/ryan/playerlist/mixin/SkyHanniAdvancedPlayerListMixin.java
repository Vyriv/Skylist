package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.SkylistPresenceManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.AdvancedPlayerList", remap = false)
public abstract class SkyHanniAdvancedPlayerListMixin {
    @ModifyArgs(
        method = "createTabLine",
        at = @At(
            value = "INVOKE",
            target = "Lat/hannibal2/skyhanni/features/misc/compacttablist/TabLine;<init>(Lnet/minecraft/class_2561;Lat/hannibal2/skyhanni/features/misc/compacttablist/TabStringType;Lnet/minecraft/class_2561;)V"
        ),
        remap = false
    )
    private void playerlist$decorateCompactTabLine(Args args) {
        Text current = (Text) args.get(2);
        if (current == null) {
            return;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        Text identified = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, current.getString());
        if (identified != current) {
            args.set(2, identified);
        }
    }
}
