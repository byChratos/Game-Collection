package de.cfe.gamecollection.backend.controller

import com.fasterxml.jackson.databind.ObjectMapper
import de.cfe.gamecollection.backend.webrtc.RoomCommand
import de.cfe.gamecollection.backend.webrtc.RoomCommandType
import de.cfe.gamecollection.backend.webrtc.RoomEvent
import de.cfe.gamecollection.backend.webrtc.RoomMembership
import de.cfe.gamecollection.backend.webrtc.WebRtcRoomService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Local control channel between a WebView and its own sidecar (/ws/room).
 *
 * The frontend used to run the WebRTC handshake itself; now it only sends JOIN/LEAVE/SEND here
 * and renders the STATUS/PEERS/MESSAGE events that come back.
 *
 * Each control connection owns a separate [RoomMembership] and only ever sees its own events —
 * several app instances on one machine share this sidecar, and they must not see each other's
 * room state or overwrite each other's peer identity.
 */
@Component
class RoomController(
    private val objectMapper: ObjectMapper,
    private val roomService: WebRtcRoomService,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(RoomController::class.java)
    private val memberships = ConcurrentHashMap<WebSocketSession, RoomMembership>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val membership = roomService.openMembership { event -> send(session, event) }
        memberships[session] = membership
        send(session, membership.snapshot())
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val membership = memberships[session] ?: return
        val command = runCatching { objectMapper.readValue(message.payload, RoomCommand::class.java) }
            .getOrElse {
                log.warn("Unlesbares Room-Kommando verworfen: {}", it.message)
                return
            }

        when (command.type) {
            RoomCommandType.JOIN -> membership.join(
                server = command.server.orEmpty(),
                roomId = command.roomId.orEmpty(),
            )

            RoomCommandType.LEAVE -> membership.leave()
            RoomCommandType.SEND -> membership.sendText(command.text.orEmpty())
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        // Once the WebView is gone (closed or reloaded) its room membership is stale, and leaving
        // keeps us from lingering as a ghost peer.
        memberships.remove(session)?.close()
    }

    private fun send(session: WebSocketSession, event: RoomEvent) {
        val payload = TextMessage(objectMapper.writeValueAsString(event))
        // WebSocketSession is not safe for concurrent senders and events arrive from several
        // libwebrtc threads; lock on the session so each control connection serialises its own.
        synchronized(session) {
            if (session.isOpen) runCatching { session.sendMessage(payload) }
        }
    }
}
