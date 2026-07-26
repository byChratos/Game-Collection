package de.cfe.gamecollection.backend.model

/**
 * A message exchanged over the P2P data channel (only [type]/[content] cross the wire) or
 * relayed to the frontend as a room chat/game event. [id]/[senderId]/[fromSelf]/[at] are attached
 * locally by whoever sent or received it — a `fromSelf` embedded by the sender would be wrong
 * once it reaches a peer — so they carry defaults and are overwritten on receipt.
 *
 * Open so [SignalMessage] can extend it as a `MessageType.CONNECTION` message with its own
 * signaling-specific fields.
 */
open class Message(
    val type: MessageType,
    val content: String,
    val id: String = "",
    val senderId: String = "",
    val fromSelf: Boolean = false,
    val at: Long = 0,
)
