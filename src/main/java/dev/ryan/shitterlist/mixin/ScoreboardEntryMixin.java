package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScoreboardEntry.class)
public abstract class ScoreboardEntryMixin {
    @Inject(method = "name", at = @At("RETURN"), cancellable = true)
    private void throwerlist$decorateScoreboardEntryName(CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null) {
            return;
        }

        Text styled = NameStyler.INSTANCE.applyScoreboardDecorations(current);
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }
}
