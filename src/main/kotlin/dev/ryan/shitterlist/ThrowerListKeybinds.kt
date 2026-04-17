package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

object ThrowerListKeybinds {
    private val category: KeyBinding.Category = KeyBinding.Category.create(Identifier.of("throwerlist", "category"))

    private val openGuiKeybind: KeyBinding = KeyBindingHelper.registerKeyBinding(
        KeyBinding(
            "key.throwerlist.open_gui",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            category,
        ),
    )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openGuiKeybind.wasPressed()) {
                if (client.currentScreen != null) {
                    continue
                }

                ThrowerListGuiLauncher.openMainScreen()
            }
        }
    }
}
