package dev.ryan.playerlist.mixin;

import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud$SidebarEntry")
public interface InGameHudSidebarEntryInvoker {
    @Invoker("<init>")
    static Object playerlist$create(Text name, Text score, int scoreWidth) {
        throw new AssertionError("Mixin constructor invoker was not transformed");
    }
}
