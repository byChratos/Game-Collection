package de.cfe.gamecollection.backend.webrtc

import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

const val DATA_CHANNEL_LABEL = "game-collection"

/**
 * One remote peer: its RTCPeerConnection plus the data channel carrying application messages.
 * Mirrors the PeerEntry type that used to live in src/webrtc/useWebRtcRoom.ts.
 *
 * libwebrtc invokes the observer methods on its own native threads, so state read from
 * elsewhere is volatile and the candidate queue is guarded.
 */
class PeerSession(
    val peerId: String,
    private val isInitiator: Boolean,
    private val onCandidate: (RTCIceCandidate) -> Unit,
    private val onStateChanged: () -> Unit,
    private val onText: (String) -> Unit,
) : PeerConnectionObserver {

    /** Assigned by [RoomSession] right after construction — the factory needs `this` as observer. */
    lateinit var connection: RTCPeerConnection

    private val lock = Any()

    @Volatile
    private var channel: RTCDataChannel? = null

    @Volatile
    var connectionState: RTCPeerConnectionState = RTCPeerConnectionState.NEW
        private set

    /** Candidates that arrive before setRemoteDescription and have to be replayed afterwards. */
    private val pendingCandidates = mutableListOf<RTCIceCandidate>()
    private var remoteDescriptionSet = false

    val channelOpen: Boolean get() = channel?.state == RTCDataChannelState.OPEN

    /** Only the initiator opens the channel; the answering side receives it via onDataChannel. */
    fun start() {
        if (isInitiator) attach(connection.createDataChannel(DATA_CHANNEL_LABEL, RTCDataChannelInit()))
    }

    override fun onIceCandidate(candidate: RTCIceCandidate) = onCandidate(candidate)

    override fun onConnectionChange(state: RTCPeerConnectionState) {
        connectionState = state
        onStateChanged()
    }

    override fun onDataChannel(dataChannel: RTCDataChannel) = attach(dataChannel)

    private fun attach(dataChannel: RTCDataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() = onStateChanged()

            override fun onMessage(buffer: RTCDataChannelBuffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onText(String(bytes, StandardCharsets.UTF_8))
            }
        })
        onStateChanged()
    }

    fun addCandidate(candidate: RTCIceCandidate) {
        synchronized(lock) {
            if (!remoteDescriptionSet) {
                pendingCandidates += candidate
                return
            }
        }
        connection.addIceCandidate(candidate)
    }

    fun flushCandidates() {
        val queued = synchronized(lock) {
            remoteDescriptionSet = true
            pendingCandidates.toList().also { pendingCandidates.clear() }
        }
        queued.forEach(connection::addIceCandidate)
    }

    /** Returns false when the channel is not open, so the caller can count actual deliveries. */
    fun send(text: String): Boolean {
        val open = channel?.takeIf { it.state == RTCDataChannelState.OPEN } ?: return false
        open.send(RTCDataChannelBuffer(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false))
        return true
    }

    fun close() {
        channel?.let {
            runCatching { it.unregisterObserver() }
            runCatching { it.close() }
        }
        channel = null
        runCatching { connection.close() }
    }

    fun info(): PeerInfo = PeerInfo(peerId, connectionState.name.lowercase(), channelOpen)
}
