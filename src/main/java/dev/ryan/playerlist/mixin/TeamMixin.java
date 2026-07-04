package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTeam.class)
public abstract class TeamMixin {
    @Inject(method = "getFormattedName(Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;", at = @At("RETURN"), cancellable = true)
    private void playerlist$decorateTeamName(Component name, CallbackInfoReturnable<MutableComponent> cir) {
        MutableComponent current = cir.getReturnValue();
        if (current == null) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(current);
        if (styled != current) {
            cir.setReturnValue((MutableComponent) styled);
        }
    }

    @Inject(method = "formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;", at = @At("RETURN"), cancellable = true)
    private static void playerlist$decorateStaticTeamName(
        Team team,
        Component name,
        CallbackInfoReturnable<MutableComponent> cir
    ) {
        MutableComponent current = cir.getReturnValue();
        if (current == null) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(current);
        if (styled != current) {
            cir.setReturnValue((MutableComponent) styled);
        }
    }
}
