package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScoreHolder.class)
public interface ScoreboardEntryMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void playerlist$decorateScoreboardEntryName(CallbackInfoReturnable<Component> cir) {
        Component current = cir.getReturnValue();
        if (current == null) {
            return;
        }

        Component styled = NameStyler.INSTANCE.applyScoreboardDisplayDecorations(current);
        if (styled != current) {
            cir.setReturnValue(styled);
        }
    }
}
