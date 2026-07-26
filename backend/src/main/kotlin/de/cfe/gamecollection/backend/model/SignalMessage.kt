package de.cfe.gamecollection.backend.model

/**
 * A WebRTC signaling message — JOIN/OFFER/ANSWER/ICE_CANDIDATE/etc. — exchanged between a room
 * session and the central signaling relay. It is a [Message] whose [type] is fixed to
 * [MessageType.CONNECTION]; [signalType] carries the actual kind of signal.
 */
class SignalMessage(
    val signalType: SignalType,
    val roomId: String,
    senderId: String,
    val targetId: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val peers: List<String>? = null,
) : Message(type = MessageType.CONNECTION, content = "", senderId = senderId)

enum class SignalType {
    JOIN,
    PEER_JOINED,
    PEER_LEFT,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    LEAVE,
}
