package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.databind.ObjectMapper
import de.cfe.gamecollection.backend.model.SignalMessage
import de.cfe.gamecollection.backend.model.SignalType
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One membership in a room: a WebSocket *client* to the central signaling server plus the peer
 * connections it negotiates. This is the Kotlin port of src/webrtc/useWebRtcRoom.ts.
 *
 * libwebrtc calls back on its own native threads. Anything that touches a peer connection is
 * therefore handed to [worker] — in particular closing a connection from inside its own observer
 * callback would otherwise risk deadlocking inside libwebrtc.
 */
class RoomSession(
    private val factory: PeerConnectionFactory,
    private val objectMapper: ObjectMapper,
    private val bases: ServerBases,
    val roomId: String,
    val localPeerId: String,
    iceServers: List<RTCIceServer>,
    private val emit: (RoomEvent) -> Unit,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(RoomSession::class.java)

    private val config = RTCConfiguration().apply { this.iceServers = iceServers }
    private val peers = ConcurrentHashMap<String, PeerSession>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "webrtc-room-$localPeerId").apply { isDaemon = true }
    }

    /** Decodes/dispatches every P2P message by kind; mirrors useWebRtcRoom.ts's messageHandler. */
    val messageHandler = P2PMessageHandler(objectMapper) { raw -> broadcast(raw) }

    private val sendLock = Any()

    @Volatile
    private var signaling: WebSocketSession? = null

    @Volatile
    private var leaving = false

    val signalingUrl: String get() = bases.signalingUrl(roomId, localPeerId)

    // --- signaling socket lifecycle -------------------------------------------------------

    override fun afterConnectionEstablished(session: WebSocketSession) {
        signaling = session
        emit(
            RoomEvent(
                type = RoomEventType.STATUS,
                status = RoomStatus.CONNECTED,
                roomId = roomId,
                localPeerId = localPeerId,
            ),
        )
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val incoming = runCatching { objectMapper.readValue(message.payload, SignalMessage::class.java) }
            .getOrElse {
                log.warn("Unlesbare Signaling-Nachricht verworfen: {}", it.message)
                return
            }

        when (incoming.signalType) {
            // We are the newcomer. Peers already in the room will send us an offer, so we just wait.
            SignalType.JOIN -> Unit
            SignalType.PEER_JOINED -> worker.execute { offerTo(incoming.senderId) }
            SignalType.OFFER -> worker.execute { handleOffer(incoming) }
            SignalType.ANSWER -> worker.execute { handleAnswer(incoming) }
            SignalType.ICE_CANDIDATE -> worker.execute { handleCandidate(incoming) }
            SignalType.PEER_LEFT -> worker.execute { closePeer(incoming.senderId) }
            SignalType.LEAVE -> Unit
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        if (leaving) return
        emit(
            RoomEvent(
                type = RoomEventType.STATUS,
                status = RoomStatus.ERROR,
                error = "Verbindung zum Signaling-Server ${bases.wsBase} wurde getrennt.",
            ),
        )
    }

    // --- handshake ------------------------------------------------------------------------

    private fun offerTo(peerId: String) {
        if (peers.containsKey(peerId)) return
        val peer = createPeer(peerId, isInitiator = true)
        peer.connection.createOfferAsync()
            .thenComposeAsync({ offer -> peer.connection.setLocalDescriptionAsync(offer).thenApply { offer } }, worker)
            .thenAcceptAsync({ offer -> sendSignal(signal(SignalType.OFFER, peerId, sdp = offer.sdp)) }, worker)
            .exceptionally { failHandshake(peerId, it) }
    }

    private fun handleOffer(incoming: SignalMessage) {
        val sdp = incoming.sdp ?: return
        val peerId = incoming.senderId
        val peer = peers[peerId] ?: createPeer(peerId, isInitiator = false)
        peer.connection.setRemoteDescriptionAsync(RTCSessionDescription(RTCSdpType.OFFER, sdp))
            .thenComposeAsync({
                peer.flushCandidates()
                peer.connection.createAnswerAsync()
            }, worker)
            .thenComposeAsync({ answer -> peer.connection.setLocalDescriptionAsync(answer).thenApply { answer } }, worker)
            .thenAcceptAsync({ answer -> sendSignal(signal(SignalType.ANSWER, peerId, sdp = answer.sdp)) }, worker)
            .exceptionally { failHandshake(peerId, it) }
    }

    private fun handleAnswer(incoming: SignalMessage) {
        val sdp = incoming.sdp ?: return
        val peer = peers[incoming.senderId] ?: return
        peer.connection.setRemoteDescriptionAsync(RTCSessionDescription(RTCSdpType.ANSWER, sdp))
            .thenRunAsync({ peer.flushCandidates() }, worker)
            .exceptionally { failHandshake(incoming.senderId, it) }
    }

    private fun handleCandidate(incoming: SignalMessage) {
        val candidate = incoming.candidate ?: return
        val peer = peers[incoming.senderId] ?: return
        peer.addCandidate(RTCIceCandidate(incoming.sdpMid.orEmpty(), incoming.sdpMLineIndex ?: 0, candidate))
    }

    private fun failHandshake(peerId: String, error: Throwable): Void? {
        log.warn("Handshake mit Peer {} fehlgeschlagen", peerId, error)
        emit(
            RoomEvent(
                type = RoomEventType.STATUS,
                status = RoomStatus.CONNECTED,
                error = "Verbindung zu Peer $peerId fehlgeschlagen: ${error.cause?.message ?: error.message}",
            ),
        )
        return null
    }

    // --- peers ----------------------------------------------------------------------------

    private fun createPeer(peerId: String, isInitiator: Boolean): PeerSession {
        val peer = PeerSession(
            peerId = peerId,
            isInitiator = isInitiator,
            onCandidate = { candidate ->
                sendSignal(
                    signal(
                        SignalType.ICE_CANDIDATE,
                        peerId,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                )
            },
            onStateChanged = { onPeerStateChanged(peerId) },
            onText = { text -> receiveText(peerId, text, fromSelf = false) },
        )
        peer.connection = factory.createPeerConnection(config, peer)
        peers[peerId] = peer
        peer.start()
        emitPeers()
        return peer
    }

    private fun onPeerStateChanged(peerId: String) {
        val peer = peers[peerId]
        val dead = peer != null &&
            (peer.connectionState == RTCPeerConnectionState.FAILED || peer.connectionState == RTCPeerConnectionState.CLOSED)
        // Never close a peer connection on the native callback thread that reported the state.
        if (dead) worker.execute { closePeer(peerId) } else emitPeers()
    }

    private fun closePeer(peerId: String) {
        peers.remove(peerId)?.close()
        emitPeers()
    }

    // --- outbound -------------------------------------------------------------------------

    /** text is already an encoded P2PMessage envelope, produced by the frontend's own handler. */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (broadcast(trimmed)) receiveText(localPeerId, trimmed, fromSelf = true)
    }

    private fun broadcast(raw: String): Boolean =
        peers.values.count { runCatching { it.send(raw) }.getOrDefault(false) } > 0

    fun close(notifyServer: Boolean) {
        leaving = true
        if (notifyServer) {
            runCatching { sendSignal(SignalMessage(signalType = SignalType.LEAVE, roomId = roomId, senderId = localPeerId)) }
        }
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        val session = signaling
        signaling = null
        runCatching { session?.close() }
        worker.shutdown()
    }

    private fun signal(
        type: SignalType,
        targetId: String,
        sdp: String? = null,
        candidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null,
    ) = SignalMessage(
        signalType = type,
        roomId = roomId,
        senderId = localPeerId,
        targetId = targetId,
        sdp = sdp,
        candidate = candidate,
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
    )

    /** WebSocketSession is not safe for concurrent senders, and ICE candidates arrive in bursts. */
    private fun sendSignal(message: SignalMessage) {
        val session = signaling ?: return
        synchronized(sendLock) {
            if (session.isOpen) session.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
        }
    }

    private fun emitPeers() {
        emit(RoomEvent(type = RoomEventType.PEERS, peers = peers.values.map { it.info() }))
    }

    /** Decodes+dispatches the envelope by kind, then mirrors it to the frontend as a chat event. */
    private fun receiveText(senderId: String, raw: String, fromSelf: Boolean) {
        val message = messageHandler.receive(
            raw,
            senderId,
            UUID.randomUUID().toString(),
            fromSelf,
            System.currentTimeMillis(),
        )
        emit(
            RoomEvent(
                type = RoomEventType.MESSAGE,
                message = RoomChatMessage(
                    id = message.id,
                    senderId = message.senderId,
                    text = raw,
                    fromSelf = message.fromSelf,
                    at = message.at,
                ),
            ),
        )
    }
}
