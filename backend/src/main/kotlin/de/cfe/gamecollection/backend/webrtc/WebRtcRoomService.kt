package de.cfe.gamecollection.backend.webrtc

import com.fasterxml.jackson.databind.ObjectMapper
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCIceServer
import jakarta.annotation.PreDestroy
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the process-wide [PeerConnectionFactory] and hands out one [RoomMembership] per control
 * connection.
 *
 * The factory is shared on purpose — libwebrtc expects a single factory per process and creating
 * connections for several memberships from it is fine. Everything else is per membership, so two
 * app instances on the same machine no longer fight over one room.
 */
@Service
class WebRtcRoomService(private val objectMapper: ObjectMapper) {

    private val lock = Any()
    private val memberships = ConcurrentHashMap.newKeySet<RoomMembership>()
    private var factory: PeerConnectionFactory? = null

    fun openMembership(emit: (RoomEvent) -> Unit): RoomMembership =
        RoomMembership(this, objectMapper, emit).also { memberships += it }

    internal fun release(membership: RoomMembership) {
        memberships -= membership
    }

    /**
     * Created on first use: the same jar running as the central signaling server only relays
     * messages and never opens a peer connection, so it should not pay for the natives.
     */
    internal fun factory(): PeerConnectionFactory = synchronized(lock) {
        factory ?: PeerConnectionFactory().also { factory = it }
    }

    internal fun fetchIceServers(bases: ServerBases): List<RTCIceServer> {
        val raw = RestClient.create()
            .get()
            .uri(bases.iceServersUrl)
            .retrieve()
            .body(object : ParameterizedTypeReference<List<IceServer>>() {})
            ?: emptyList()

        // Drop entries libwebrtc would reject (empty urls, half-filled TURN credentials).
        return raw.filter { it.urls.isNotBlank() }.map { server ->
            RTCIceServer().apply {
                urls = listOf(server.urls)
                if (!server.username.isNullOrBlank() && !server.credential.isNullOrBlank()) {
                    username = server.username
                    password = server.credential
                }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        // close() removes the membership from the set, so iterate a copy.
        memberships.toList().forEach { runCatching { it.close() } }
        synchronized(lock) {
            runCatching { factory?.dispose() }
            factory = null
        }
    }
}
