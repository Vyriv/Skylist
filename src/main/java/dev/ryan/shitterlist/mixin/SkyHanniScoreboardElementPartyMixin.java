package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.NameStyler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.gui.customscoreboard.elements.ScoreboardElementParty", remap = false)
public abstract class SkyHanniScoreboardElementPartyMixin {
    @Inject(method = "getDisplay()Ljava/util/List;", at = @At("RETURN"), cancellable = true, remap = false)
    private void throwerlist$decoratePartyDisplay(CallbackInfoReturnable<List<String>> cir) {
        List<String> current = cir.getReturnValue();
        if (current == null || current.isEmpty()) {
            return;
        }

        List<String> decorated = null;
        for (int index = 0; index < current.size(); index++) {
            String line = current.get(index);
            String styled = NameStyler.INSTANCE.applyScoreboardDecorationsToString(line);
            if (styled == line) {
                if (decorated != null) {
                    decorated.add(line);
                }
                continue;
            }

            if (decorated == null) {
                decorated = new java.util.ArrayList<>(current.subList(0, index));
            }
            decorated.add(styled);
        }

        if (decorated != null) {
            cir.setReturnValue(decorated);
        }
    }
}
