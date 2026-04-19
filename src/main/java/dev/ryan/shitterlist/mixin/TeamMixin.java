package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Team.class)
public abstract class TeamMixin {
    @Inject(method = "decorateName(Lnet/minecraft/text/Text;)Lnet/minecraft/text/MutableText;", at = @At("RETURN"), cancellable = true)
    private void throwerlist$decorateTeamName(Text name, CallbackInfoReturnable<MutableText> cir) {
        MutableText current = cir.getReturnValue();
        if (current == null || !NameStyler.INSTANCE.containsStyledScoreboardTargetName(current.getString())) {
            return;
        }

        cir.setReturnValue(NameStyler.INSTANCE.applyScoreboardDecorations(current).copy());
    }

    @Inject(method = "decorateName(Lnet/minecraft/scoreboard/AbstractTeam;Lnet/minecraft/text/Text;)Lnet/minecraft/text/MutableText;", at = @At("RETURN"), cancellable = true)
    private static void throwerlist$decorateStaticTeamName(
        AbstractTeam team,
        Text name,
        CallbackInfoReturnable<MutableText> cir
    ) {
        MutableText current = cir.getReturnValue();
        if (current == null || !NameStyler.INSTANCE.containsStyledScoreboardTargetName(current.getString())) {
            return;
        }

        cir.setReturnValue(NameStyler.INSTANCE.applyScoreboardDecorations(current).copy());
    }
}
