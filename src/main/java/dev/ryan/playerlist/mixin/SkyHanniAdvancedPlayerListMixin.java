package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.network.chat.Component;
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
            target = "Lat/hannibal2/skyhanni/features/misc/compacttablist/TabLine;<init>(Lnet/minecraft/network/chat/Component;Lat/hannibal2/skyhanni/features/misc/compacttablist/TabStringType;Lnet/minecraft/network/chat/Component;)V"
        ),
        remap = false
    )
    private void playerlist$decorateCompactTabLine(Args args) {
        Component current = (Component) args.get(2);
        if (current == null) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyNameplateDisplayDecorations(current);
        if (styled != current) {
            args.set(2, styled);
        }
    }
}
