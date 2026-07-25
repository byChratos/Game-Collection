package de.cfe.gamecollection.backend.webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import java.util.concurrent.CompletableFuture

/**
 * webrtc-java exposes the SDP calls through observer callbacks. These adapters turn them into
 * CompletableFutures so the handshake can be chained without blocking the signaling thread —
 * the browser's promise-based API did the same thing for free.
 */

fun RTCPeerConnection.createOfferAsync(): CompletableFuture<RTCSessionDescription> =
    describeAsync { observer -> createOffer(RTCOfferOptions(), observer) }

fun RTCPeerConnection.createAnswerAsync(): CompletableFuture<RTCSessionDescription> =
    describeAsync { observer -> createAnswer(RTCAnswerOptions(), observer) }

fun RTCPeerConnection.setLocalDescriptionAsync(description: RTCSessionDescription): CompletableFuture<Void?> =
    applyAsync { observer -> setLocalDescription(description, observer) }

fun RTCPeerConnection.setRemoteDescriptionAsync(description: RTCSessionDescription): CompletableFuture<Void?> =
    applyAsync { observer -> setRemoteDescription(description, observer) }

private fun describeAsync(
    start: (CreateSessionDescriptionObserver) -> Unit,
): CompletableFuture<RTCSessionDescription> {
    val future = CompletableFuture<RTCSessionDescription>()
    start(object : CreateSessionDescriptionObserver {
        override fun onSuccess(description: RTCSessionDescription) {
            future.complete(description)
        }

        override fun onFailure(error: String) {
            future.completeExceptionally(IllegalStateException("SDP creation failed: $error"))
        }
    })
    return future
}

private fun applyAsync(start: (SetSessionDescriptionObserver) -> Unit): CompletableFuture<Void?> {
    val future = CompletableFuture<Void?>()
    start(object : SetSessionDescriptionObserver {
        override fun onSuccess() {
            future.complete(null)
        }

        override fun onFailure(error: String) {
            future.completeExceptionally(IllegalStateException("setDescription failed: $error"))
        }
    })
    return future
}
