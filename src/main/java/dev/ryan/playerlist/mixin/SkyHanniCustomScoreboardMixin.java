package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.integration.SkyHanniRenderableAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard", remap = false)
public abstract class SkyHanniCustomScoreboardMixin {
    @Redirect(
        method = "createRenderable(Ljava/util/List;)Lat/hannibal2/skyhanni/utils/renderables/container/VerticalContainerRenderable;",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Collection;add(Ljava/lang/Object;)Z"
        ),
        remap = false
    )
    private boolean playerlist$replaceSkyHanniRenderable(Collection<Object> renderables, Object renderable) {
        Object replacement = SkyHanniRenderableAdapter.createStyledRenderable(renderable);
        return renderables.add(replacement != null ? replacement : renderable);
    }
}
