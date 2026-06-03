package dev.ryan.playerlist.mixin;

import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud$SidebarEntry")
public interface InGameHudSidebarEntryAccessor {
    @Accessor("name")
    Text playerlist$getName();

    @Accessor("score")
    Text playerlist$getScore();

    @Accessor("scoreWidth")
    int playerlist$getScoreWidth();
}
