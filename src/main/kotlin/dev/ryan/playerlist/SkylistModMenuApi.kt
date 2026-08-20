package dev.ryan.playerlist

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.minecraft.client.gui.screens.Screen

/**
 * Wires Skylist's main screen into ModMenu's "Mods" config button. Only loaded by Fabric Loader
 * if ModMenu is actually installed (see the "modmenu" entrypoint in fabric.mod.json) - Skylist
 * has no hard dependency on it.
 */
class SkylistModMenuApi : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<Screen> =
        ConfigScreenFactory { SkylistMainScreen() }
}
