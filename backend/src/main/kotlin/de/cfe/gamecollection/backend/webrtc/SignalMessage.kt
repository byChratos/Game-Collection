package de.cfe.gamecollection.backend.webrtc

data class SignalMessage(
    val type: SignalType,
    val roomId: String,
    val senderId: String,
    val targetId: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val peers: List<String>? = null,
)

enum class SignalType {
    JOIN,
    PEER_JOINED,
    PEER_LEFT,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    LEAVE,
}
