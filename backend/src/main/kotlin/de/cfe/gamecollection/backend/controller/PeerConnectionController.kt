package de.cfe.gamecollection.backend.controller

import com.fasterxml.jackson.databind.ObjectMapper
import de.cfe.gamecollection.backend.model.SignalMessage
import de.cfe.gamecollection.backend.model.SignalType
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Component
class PeerConnectionController(
    private val objectMapper: ObjectMapper,
) : TextWebSocketHandler() {

    private val rooms = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val (roomId, peerId) = session.roomAndPeerId() ?: run {
            session.close(CloseStatus.BAD_DATA.withReason("roomId and peerId query parameters are required"))
            return
        }

        val room = rooms.computeIfAbsent(roomId) { ConcurrentHashMap() }
        // Snapshot, register and notify atomically: if two peers joined concurrently both could
        // see an empty room and both would end up sending an offer to each other (SDP glare).
        synchronized(room) {
            val existingPeerIds = room.keys.toList()
            room[peerId] = session

            send(session, SignalMessage(signalType = SignalType.JOIN, roomId = roomId, senderId = peerId, peers = existingPeerIds))
            broadcast(room, exclude = peerId, message = SignalMessage(signalType = SignalType.PEER_JOINED, roomId = roomId, senderId = peerId))
        }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val incoming = objectMapper.readValue(message.payload, SignalMessage::class.java)
        val room = rooms[incoming.roomId] ?: return

        when (incoming.signalType) {
            SignalType.OFFER, SignalType.ANSWER, SignalType.ICE_CANDIDATE -> {
                val targetId = incoming.targetId ?: return
                room[targetId]?.let { send(it, incoming) }
            }
            SignalType.LEAVE -> handleLeave(session)
            SignalType.JOIN, SignalType.PEER_JOINED, SignalType.PEER_LEFT -> Unit
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        handleLeave(session)
    }

    private fun handleLeave(session: WebSocketSession) {
        val (roomId, peerId) = session.roomAndPeerId() ?: return
        val room = rooms[roomId] ?: return
        room.remove(peerId)
        if (room.isEmpty()) {
            rooms.remove(roomId)
        } else {
            broadcast(room, exclude = peerId, message = SignalMessage(signalType = SignalType.PEER_LEFT, roomId = roomId, senderId = peerId))
        }
    }

    private fun broadcast(room: Map<String, WebSocketSession>, exclude: String, message: SignalMessage) {
        room.forEach { (peerId, peerSession) ->
            if (peerId != exclude) send(peerSession, message)
        }
    }

    private fun send(session: WebSocketSession, message: SignalMessage) {
        if (session.isOpen) {
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
        }
    }

    private fun WebSocketSession.roomAndPeerId(): Pair<String, String>? {
        val query = uri?.query ?: return null
        val params = query.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8) else null
        }.toMap()
        val roomId = params["roomId"]?.takeIf { it.isNotBlank() } ?: return null
        val peerId = params["peerId"]?.takeIf { it.isNotBlank() } ?: return null
        return roomId to peerId
    }
}
