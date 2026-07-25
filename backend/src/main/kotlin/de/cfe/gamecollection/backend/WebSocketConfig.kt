package de.cfe.gamecollection.backend

import de.cfe.gamecollection.backend.controller.PeerConnectionController
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val peerConnectionController: PeerConnectionController,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(peerConnectionController, "/ws/signaling")
            .setAllowedOrigins("http://localhost:1420", "tauri://localhost", "https://tauri.localhost")
    }
}
