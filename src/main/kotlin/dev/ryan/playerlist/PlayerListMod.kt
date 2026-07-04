package dev.ryan.playerlist

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PlayerListMod : ClientModInitializer {
    override fun onInitializeClient() {
        ConfigManager.load()
        ContentManager.load()
        UiLayoutManager.load()
        ThemeManager.load()
        CapeTextureManager.initialize()
        PlayerCustomizationRegistry.initialize()
        ContentManager.fetchRemotePeopleOnStartup()
        SkylistPresenceManager.initialize()

        PlayerListGuiLauncher.register()
        PlayerListKeybinds.register()
        OutgoingNameRewrite.register()

        ScammerListManager.start()
        ScammerCheckService.start()
        GitHubUpdateChecker.register()

        listener = PartyListener(client)
        listener.register()
        SkylistBaseCommandHandler.register()

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            listener.reset()
        }
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger("Skylist")
        lateinit var listener: PartyListener
            private set

        val client: Minecraft
            get() = Minecraft.getInstance()
    }
}
