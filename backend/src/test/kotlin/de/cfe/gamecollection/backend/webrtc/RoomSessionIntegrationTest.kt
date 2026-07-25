package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.databind.ObjectMapper
import dev.onvoid.webrtc.PeerConnectionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof that the WebRTC logic now lives in Kotlin: two room sessions discover each
 * other through the real signaling relay, negotiate a peer connection and exchange a message
 * over the data channel. No browser involved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomSessionIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private class Recorder {
        val messages = CopyOnWriteArrayList<RoomChatMessage>()
        val channelOpen = CountDownLatch(1)
        val remoteMessage = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()

        fun accept(event: RoomEvent) {
            event.error?.let { errors += it }
            when (event.type) {
                RoomEventType.PEERS ->
                    if (event.peers?.any { it.channelOpen } == true) channelOpen.countDown()

                RoomEventType.MESSAGE -> event.message?.let {
                    messages += it
                    if (!it.fromSelf) remoteMessage.countDown()
                }

                RoomEventType.STATUS -> Unit
            }
        }
    }

    @Test
    fun `two sessions negotiate through the signaling server and exchange a message`() {
        val factory = PeerConnectionFactory()
        val bases = ServerBases.parse("localhost:$port")
        val client = StandardWebSocketClient()

        val hostRecorder = Recorder()
        val guestRecorder = Recorder()

        val host = RoomSession(factory, objectMapper, bases, "abendrunde", "peer-host", emptyList(), hostRecorder::accept)
        val guest = RoomSession(factory, objectMapper, bases, "abendrunde", "peer-guest", emptyList(), guestRecorder::accept)

        try {
            // The host has to be registered first: the server only announces PEER_JOINED to peers
            // already in the room, and that announcement is what triggers the offer.
            client.execute(host, host.signalingUrl).get(15, TimeUnit.SECONDS)
            client.execute(guest, guest.signalingUrl).get(15, TimeUnit.SECONDS)

            assertTrue(hostRecorder.channelOpen.await(30, TimeUnit.SECONDS), "host data channel never opened")
            assertTrue(guestRecorder.channelOpen.await(30, TimeUnit.SECONDS), "guest data channel never opened")

            host.sendText("hallo aus dem kotlin sidecar")

            assertTrue(guestRecorder.remoteMessage.await(30, TimeUnit.SECONDS), "guest never received the message")

            val received = guestRecorder.messages.single { !it.fromSelf }
            assertEquals("hallo aus dem kotlin sidecar", received.text)
            assertEquals("peer-host", received.senderId)

            // The sender echoes its own message locally, exactly as the old TS hook did.
            assertTrue(hostRecorder.messages.any { it.fromSelf && it.text == "hallo aus dem kotlin sidecar" })
            assertTrue(hostRecorder.errors.isEmpty(), "host reported errors: ${hostRecorder.errors}")
        } finally {
            guest.close(notifyServer = true)
            host.close(notifyServer = true)
            factory.dispose()
        }
    }
}
