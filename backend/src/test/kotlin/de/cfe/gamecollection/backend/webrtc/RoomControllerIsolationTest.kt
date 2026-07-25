package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the path the app actually takes — /ws/room -> RoomController -> RoomMembership — which
 * [RoomSessionIntegrationTest] skips by constructing RoomSession directly.
 *
 * Regression: the frontend hardcodes ws://localhost:8721/ws/room, so two app instances on one
 * machine land on the same sidecar. While the sidecar held a single global room session, the
 * second JOIN tore down the first instance's membership and both WebViews were broadcast the
 * same events — no second peer ever existed, so nothing connected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomControllerIsolationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val timeout = 30L

    private inner class ControlClient(val tag: String) : TextWebSocketHandler() {
        val events = CopyOnWriteArrayList<RoomEvent>()
        val errors = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(1)
        val channelOpen = CountDownLatch(1)
        val remoteMessage = CountDownLatch(1)

        @Volatile
        var localPeerId: String? = null

        private lateinit var session: WebSocketSession

        override fun afterConnectionEstablished(session: WebSocketSession) {
            this.session = session
        }

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            val event = objectMapper.readValue(message.payload, RoomEvent::class.java)
            events += event
            event.error?.let { errors += it }
            when (event.type) {
                RoomEventType.STATUS -> {
                    event.localPeerId?.let { localPeerId = it }
                    if (event.status == RoomStatus.CONNECTED) connected.countDown()
                }

                RoomEventType.PEERS ->
                    if (event.peers?.any { it.channelOpen } == true) channelOpen.countDown()

                RoomEventType.MESSAGE -> event.message?.let {
                    if (!it.fromSelf) remoteMessage.countDown()
                }
            }
        }

        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) = Unit

        fun send(command: Map<String, String>) {
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(command)))
        }

        fun close() = session.close()
    }

    @Test
    fun `two control connections on one sidecar keep separate memberships and reach each other`() {
        val client = StandardWebSocketClient()
        val controlUrl = "ws://localhost:$port/ws/room"

        val first = ControlClient("first")
        val second = ControlClient("second")

        client.execute(first, controlUrl).get(15, TimeUnit.SECONDS)
        client.execute(second, controlUrl).get(15, TimeUnit.SECONDS)

        try {
            // The first instance has to be registered before the second joins: the relay only
            // announces PEER_JOINED to peers already in the room, and that triggers the offer.
            first.send(mapOf("type" to "JOIN", "server" to "localhost:$port", "roomId" to "abendrunde"))
            assertTrue(first.connected.await(timeout, TimeUnit.SECONDS), "first never reached CONNECTED")

            second.send(mapOf("type" to "JOIN", "server" to "localhost:$port", "roomId" to "abendrunde"))
            assertTrue(second.connected.await(timeout, TimeUnit.SECONDS), "second never reached CONNECTED")

            // The bug: the second JOIN used to replace the shared session, so both connections
            // ended up reporting the *same* identity.
            assertNotEquals(
                first.localPeerId,
                second.localPeerId,
                "both control connections report the same peer id — memberships are not isolated",
            )

            assertTrue(first.channelOpen.await(timeout, TimeUnit.SECONDS), "first data channel never opened")
            assertTrue(second.channelOpen.await(timeout, TimeUnit.SECONDS), "second data channel never opened")

            first.send(mapOf("type" to "SEND", "text" to "hallo von instanz eins"))

            assertTrue(second.remoteMessage.await(timeout, TimeUnit.SECONDS), "second never received the message")

            val received = second.events.mapNotNull { it.message }.single { !it.fromSelf }
            assertEquals("hallo von instanz eins", received.text)
            assertEquals(first.localPeerId, received.senderId)

            // Each connection only ever sees its own membership's events.
            assertTrue(
                first.events.mapNotNull { it.message }.none { !it.fromSelf },
                "first saw the second connection's inbound message — events are being broadcast",
            )
            assertTrue(first.errors.isEmpty(), "first reported errors: ${first.errors}")
            assertTrue(second.errors.isEmpty(), "second reported errors: ${second.errors}")
        } finally {
            runCatching { second.close() }
            runCatching { first.close() }
        }
    }
}
