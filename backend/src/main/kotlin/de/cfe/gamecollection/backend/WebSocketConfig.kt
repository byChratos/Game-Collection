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
        registry.addHandler(peerConnectionController, "/ws/signaling")
            .setAllowedOrigins(*appOrigins)

        registry.addHandler(roomController, "/ws/room")
            .setAllowedOrigins(*appOrigins)
    }
}
