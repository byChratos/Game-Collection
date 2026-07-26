package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import java.util.UUID
import java.util.concurrent.Executors

/**
 * One WebView's membership in one room.
 *
 * Everything that used to be process-wide state on [WebRtcRoomService] lives here instead: the
 * signaling session, the status and the peer identity. That matters because the frontend hardcodes
 * ws://localhost:8721/ws/room, so a second app instance on the same machine lands on this very
 * sidecar — with a single shared membership its JOIN would tear down the first instance's room.
 *
 * Events are emitted only to the control connection that owns this membership.
 */
class RoomMembership internal constructor(
    private val service: WebRtcRoomService,
    private val objectMapper: ObjectMapper,
    private val emit: (RoomEvent) -> Unit,
) {

    private val log = LoggerFactory.getLogger(RoomMembership::class.java)
    private val lock = Any()

    /** Single-threaded so a JOIN arriving right after another cannot interleave its teardown. */
    private val joiner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "webrtc-join").apply { isDaemon = true }
    }

    private var session: RoomSession? = null

    @Volatile
    private var status: RoomStatus = RoomStatus.IDLE

    @Volatile
    private var closed = false

    fun snapshot(): RoomEvent {
        val current = synchronized(lock) { session }
        return RoomEvent(
            type = RoomEventType.STATUS,
            status = status,
            roomId = current?.roomId,
            localPeerId = current?.localPeerId,
        )
    }

    /** Joining does blocking I/O (ICE lookup, WebSocket connect), so it runs off the caller's thread. */
    fun join(server: String, roomId: String) {
        if (closed) return
        joiner.execute { joinBlocking(server, roomId) }
    }

    private fun joinBlocking(server: String, roomId: String) {
        val trimmedRoom = roomId.trim()
        if (trimmedRoom.isEmpty()) {
            fail("Bitte einen Identifier für die Session angeben.")
            return
        }

        val bases = try {
            ServerBases.parse(server)
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Ungültige Serveradresse.")
            return
        }

        leave()
        if (closed) return
        status = RoomStatus.CONNECTING
        publish(RoomEvent(type = RoomEventType.STATUS, status = RoomStatus.CONNECTING))

        // Without STUN/TURN only host candidates are gathered, which still covers a shared LAN.
        val iceServers = try {
            service.fetchIceServers(bases)
        } catch (error: Exception) {
            log.warn("ICE-Server konnten nicht geladen werden", error)
            publish(
                RoomEvent(
                    type = RoomEventType.STATUS,
                    status = RoomStatus.CONNECTING,
                    warning = "ICE-Server konnten nicht geladen werden – Verbindungen funktionieren nur im lokalen Netz.",
                ),
            )
            emptyList()
        }

        val room = synchronized(lock) {
            RoomSession(
                factory = service.factory(),
                objectMapper = objectMapper,
                bases = bases,
                roomId = trimmedRoom,
                localPeerId = UUID.randomUUID().toString().take(8),
                iceServers = iceServers,
                emit = ::publish,
            ).also { session = it }
        }

        StandardWebSocketClient().execute(room, room.signalingUrl).whenComplete { _, error ->
            if (error != null) {
                log.warn("Signaling-Server nicht erreichbar: {}", room.signalingUrl, error)
                synchronized(lock) {
                    if (session === room) {
                        room.close(notifyServer = false)
                        session = null
                    }
                }
                fail("Signaling-Server ${bases.wsBase} nicht erreichbar: ${error.cause?.message ?: error.message}")
            } else {
                status = RoomStatus.CONNECTED
            }
        }
    }

    fun leave() {
        val previous = synchronized(lock) { session.also { session = null } }
        previous?.close(notifyServer = true)
        status = RoomStatus.IDLE
    }

    fun sendText(text: String) {
        synchronized(lock) { session }?.sendText(text)
    }

    /** For backend-side controllers (e.g. a ChessController) to send/receive their own message kinds. */
    fun messageHandler(): P2PMessageHandler? = synchronized(lock) { session }?.messageHandler

    /** Called when the owning control connection goes away; the membership is not reusable after. */
    fun close() {
        closed = true
        leave()
        joiner.shutdown()
        service.release(this)
    }

    private fun fail(message: String) {
        status = RoomStatus.ERROR
        publish(RoomEvent(type = RoomEventType.STATUS, status = RoomStatus.ERROR, error = message))
    }

    /** Late callbacks from libwebrtc must not reach a control connection that is already gone. */
    private fun publish(event: RoomEvent) {
        if (!closed) emit(event)
    }
}
