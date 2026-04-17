package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen

object ThrowerListGuiLauncher {
    data class ScreenRequest(
        val view: String = View.DEFAULT,
        val initialSearch: String = "",
    )

    object View {
        const val DEFAULT = "default"
        const val ALL = "all"
        const val LOCAL = "local"
        const val REMOTE = "remote"
        const val SCAMMERS = "scammers"
    }

    private var pendingRequest: ScreenRequest? = null
    private var registered = false
    private var provider: (ScreenRequest) -> Screen = { request ->
        SkylistMainScreen(initialSearch = request.initialSearch)
    }

    fun register() {
        if (registered) {
            return
        }

        registered = true
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val request = pendingRequest ?: return@register
            if (client.currentScreen is ChatScreen) {
                client.setScreen(null)
                return@register
            }

            pendingRequest = null
            client.setScreen(provider(request))
        }
    }

    fun registerMainScreenProvider(provider: (ScreenRequest) -> Screen) {
        this.provider = provider
    }

    fun openMainScreen(view: Any? = View.DEFAULT, initialSearch: String = "") {
        val normalizedView = when (view) {
            null -> View.DEFAULT
            is String -> view.lowercase()
            else -> view.toString().lowercase()
        }
        pendingRequest = ScreenRequest(view = normalizedView, initialSearch = initialSearch)
    }
}
