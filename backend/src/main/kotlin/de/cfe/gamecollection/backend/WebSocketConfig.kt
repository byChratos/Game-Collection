package de.cfe.gamecollection.backend

import de.cfe.gamecollection.backend.controller.PeerConnectionController
import de.cfe.gamecollection.backend.controller.RoomController
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val peerConnectionController: PeerConnectionController,
    private val roomController: RoomController,
) : WebSocketConfigurer {

    private val appOrigins = arrayOf("http://localhost:1420", "tauri://localhost", "https://tauri.localhost")

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // Signaling relay, reached by *other* machines' sidecars. The allow-list only constrains
        // browsers: sidecar clients send no Origin header at all, and Spring lets those through.
        registry.addHandler(peerConnectionController, "/ws/signaling")
            .setAllowedOrigins(*appOrigins)

        // Local control channel: only ever the WebView on this machine.
        registry.addHandler(roomController, "/ws/room")
            .setAllowedOrigins(*appOrigins)
    }
}
