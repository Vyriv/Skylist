package dev.ryan.playerlist.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Mutable;

@Mixin(targets = "net.minecraft.client.gui.Gui$1DisplayEntry")
public interface InGameHudSidebarEntryAccessor {
    @Accessor("name")
    Component playerlist$getName();

    @Mutable
    @Accessor("name")
    void playerlist$setName(Component name);

    @Accessor("score")
    Component playerlist$getScore();

    @Accessor("scoreWidth")
    int playerlist$getScoreWidth();
}
