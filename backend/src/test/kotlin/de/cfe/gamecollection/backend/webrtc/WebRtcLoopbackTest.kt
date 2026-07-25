package de.cfe.gamecollection.backend.webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that the JNI natives load and that a full WebRTC handshake runs inside the JVM:
 * two peer connections in this process negotiate over host candidates (no STUN/TURN needed)
 * and exchange a message over an SCTP data channel.
 */
class WebRtcLoopbackTest {

    private val timeout = 20L

    @Test
    fun `two peer connections exchange a message over a data channel`() {
        val factory = PeerConnectionFactory()

        // Candidates can arrive before the remote description is set, which libwebrtc rejects.
        val offerPending = mutableListOf<RTCIceCandidate>()
        val answerPending = mutableListOf<RTCIceCandidate>()
        var offerRemoteSet = false
        var answerRemoteSet = false

        val offerConnection = AtomicReference<RTCPeerConnection>()
        val answerConnection = AtomicReference<RTCPeerConnection>()

        val received = AtomicReference<String>()
        val messageLatch = CountDownLatch(1)
        val remoteChannelOpen = CountDownLatch(1)

        val offerObserver = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                synchronized(offerPending) {
                    if (answerRemoteSet) answerConnection.get()?.addIceCandidate(candidate)
                    else answerPending += candidate
                }
            }
        }

        val answerObserver = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                synchronized(offerPending) {
                    if (offerRemoteSet) offerConnection.get()?.addIceCandidate(candidate)
                    else offerPending += candidate
                }
            }

            override fun onDataChannel(dataChannel: RTCDataChannel) {
                dataChannel.registerObserver(object : RTCDataChannelObserver {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit

                    override fun onStateChange() {
                        if (dataChannel.state == RTCDataChannelState.OPEN) remoteChannelOpen.countDown()
                    }

                    override fun onMessage(buffer: RTCDataChannelBuffer) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        received.set(String(bytes, StandardCharsets.UTF_8))
                        messageLatch.countDown()
                    }
                })
            }
        }

        val config = RTCConfiguration()
        val offerPc = factory.createPeerConnection(config, offerObserver)
        val answerPc = factory.createPeerConnection(config, answerObserver)
        offerConnection.set(offerPc)
        answerConnection.set(answerPc)

        try {
            val channel = offerPc.createDataChannel("game-collection", RTCDataChannelInit())
            val localChannelOpen = CountDownLatch(1)
            channel.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    if (channel.state == RTCDataChannelState.OPEN) localChannelOpen.countDown()
                }

                override fun onMessage(buffer: RTCDataChannelBuffer) = Unit
            })

            val offer = offerPc.createOfferBlocking()
            offerPc.setLocalDescriptionBlocking(offer)
            answerPc.setRemoteDescriptionBlocking(offer)
            synchronized(offerPending) {
                answerRemoteSet = true
                answerPending.forEach(answerPc::addIceCandidate)
                answerPending.clear()
            }

            val answer = answerPc.createAnswerBlocking()
            answerPc.setLocalDescriptionBlocking(answer)
            offerPc.setRemoteDescriptionBlocking(answer)
            synchronized(offerPending) {
                offerRemoteSet = true
                offerPending.forEach(offerPc::addIceCandidate)
                offerPending.clear()
            }

            assertTrue(localChannelOpen.await(timeout, TimeUnit.SECONDS), "local data channel never opened")
            assertTrue(remoteChannelOpen.await(timeout, TimeUnit.SECONDS), "remote data channel never opened")

            val payload = "hallo aus kotlin"
            channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8)), false))

            assertTrue(messageLatch.await(timeout, TimeUnit.SECONDS), "message never arrived")
            assertEquals(payload, received.get())
        } finally {
            offerPc.close()
            answerPc.close()
            factory.dispose()
        }
    }
}

private fun RTCPeerConnection.createOfferBlocking(): RTCSessionDescription =
    awaitDescription { observer -> createOffer(RTCOfferOptions(), observer) }

private fun RTCPeerConnection.createAnswerBlocking(): RTCSessionDescription =
    awaitDescription { observer -> createAnswer(RTCAnswerOptions(), observer) }

private fun awaitDescription(start: (CreateSessionDescriptionObserver) -> Unit): RTCSessionDescription {
    val result = AtomicReference<RTCSessionDescription>()
    val failure = AtomicReference<String>()
    val latch = CountDownLatch(1)
    start(object : CreateSessionDescriptionObserver {
        override fun onSuccess(description: RTCSessionDescription) {
            result.set(description)
            latch.countDown()
        }

        override fun onFailure(error: String) {
            failure.set(error)
            latch.countDown()
        }
    })
    check(latch.await(20, TimeUnit.SECONDS)) { "SDP creation timed out" }
    failure.get()?.let { error("SDP creation failed: $it") }
    return result.get()
}

private fun RTCPeerConnection.setLocalDescriptionBlocking(description: RTCSessionDescription) =
    awaitSet { observer -> setLocalDescription(description, observer) }

private fun RTCPeerConnection.setRemoteDescriptionBlocking(description: RTCSessionDescription) =
    awaitSet { observer -> setRemoteDescription(description, observer) }

private fun awaitSet(start: (SetSessionDescriptionObserver) -> Unit) {
    val failure = AtomicReference<String>()
    val latch = CountDownLatch(1)
    start(object : SetSessionDescriptionObserver {
        override fun onSuccess() = latch.countDown()

        override fun onFailure(error: String) {
            failure.set(error)
            latch.countDown()
        }
    })
    check(latch.await(20, TimeUnit.SECONDS)) { "setDescription timed out" }
    failure.get()?.let { error("setDescription failed: $it") }
}
