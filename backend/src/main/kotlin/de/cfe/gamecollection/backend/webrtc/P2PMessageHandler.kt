package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.databind.ObjectMapper
import de.cfe.gamecollection.backend.model.Message
import de.cfe.gamecollection.backend.model.MessageType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Sends and receives [Message]s over the room's data channel.
 *
 * Owns no transport itself — it is handed a `sendRaw` callback (the actual data channel
 * broadcast) and fed every incoming envelope via [receive], which decodes it and dispatches to
 * whoever subscribed for that [MessageType] via [on]. Mirrors src/webrtc/P2PMessageHandler.ts: a
 * Kotlin-side controller (e.g. a server-authoritative ChessController) can subscribe here the same
 * way a frontend one subscribes on the TS handler, without either side knowing the other exists.
 */
class P2PMessageHandler(
    private val objectMapper: ObjectMapper,
    private val sendRaw: (String) -> Unit,
) {
    private val listeners = ConcurrentHashMap<MessageType, CopyOnWriteArraySet<(Message) -> Unit>>()

    /** Only type/content go over the wire — id/senderId/fromSelf/at are attached on receipt. */
    fun send(type: MessageType, content: String) {
        sendRaw(objectMapper.writeValueAsString(mapOf("type" to type, "content" to content)))
    }

    /** Called by RoomSession for every message that arrives, whether from a peer or the local side. */
    fun receive(raw: String, senderId: String, id: String, fromSelf: Boolean, at: Long): Message {
        val decoded = runCatching { objectMapper.readValue(raw, Message::class.java) }
            .getOrElse { Message(MessageType.CHAT, raw) }
        val message = Message(decoded.type, decoded.content, id = id, senderId = senderId, fromSelf = fromSelf, at = at)
        listeners[message.type]?.forEach { it(message) }
        return message
    }

    /** Subscribe to messages of a given type, e.g. handler.on(MessageType.CHESS) { message -> ... }. */
    fun on(type: MessageType, listener: (Message) -> Unit): () -> Unit {
        val set = listeners.getOrPut(type) { CopyOnWriteArraySet() }
        set += listener
        return { set -= listener }
    }
}
