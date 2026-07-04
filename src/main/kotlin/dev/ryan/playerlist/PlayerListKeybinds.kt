package dev.ryan.playerlist

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

object PlayerListKeybinds {
    private val category: KeyMapping.Category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("playerlist", "category"))

    private val openGuiKeybind: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.playerlist.open_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            category,
        ),
    )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openGuiKeybind.consumeClick()) {
                if (client.currentScreen != null) {
                    continue
                }

                PlayerListGuiLauncher.openMainScreen()
            }
        }
    }
}
