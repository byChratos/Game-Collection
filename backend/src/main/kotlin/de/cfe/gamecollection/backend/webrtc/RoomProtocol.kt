package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Control protocol between the WebView and its own local sidecar over /ws/room.
 *
 * This is deliberately *not* the signaling protocol (see [de.cfe.gamecollection.backend.model.SignalMessage]):
 * the frontend no longer takes part in the WebRTC handshake at all, it only issues commands and
 * renders state.
 */
data class RoomCommand(
    val type: RoomCommandType,
    /** Address of the central signaling server, as typed by the user. Only used by JOIN. */
    val server: String? = null,
    val roomId: String? = null,
    val text: String? = null,
)

enum class RoomCommandType {
    JOIN,
    LEAVE,
    SEND,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RoomEvent(
    val type: RoomEventType,
    val status: RoomStatus? = null,
    val roomId: String? = null,
    val localPeerId: String? = null,
    val error: String? = null,
    val warning: String? = null,
    val peers: List<PeerInfo>? = null,
    val message: RoomChatMessage? = null,
)

enum class RoomEventType {
    STATUS,
    PEERS,
    MESSAGE,
}

enum class RoomStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class PeerInfo(
    val peerId: String,
    /** Lowercased RTCPeerConnectionState, matching the values the DOM API used to report. */
    val connectionState: String,
    val channelOpen: Boolean,
)

data class RoomChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val fromSelf: Boolean,
    val at: Long,
)
