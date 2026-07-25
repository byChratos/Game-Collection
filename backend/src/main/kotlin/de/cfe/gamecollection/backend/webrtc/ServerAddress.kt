package de.cfe.gamecollection.backend.webrtc

import java.net.URI

/**
 * HTTP and WebSocket base URLs of the central signaling server.
 *
 * Ported from parseServerAddress() in the former src/webrtc/signaling.ts.
 */
data class ServerBases(val httpBase: String, val wsBase: String) {

    fun signalingUrl(roomId: String, peerId: String): String =
        "$wsBase/ws/signaling?roomId=${roomId.urlEncoded()}&peerId=${peerId.urlEncoded()}"

    val iceServersUrl: String get() = "$httpBase/api/webrtc/ice-servers"

    companion object {
        private const val DEFAULT_PORT = 8721
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

        /**
         * Accepts what the user types on the start screen: "192.168.1.5", "localhost:8721"
         * or "https://signal.example.com".
         */
        fun parse(input: String): ServerBases {
            val trimmed = input.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Bitte eine Adresse für den Signaling-Server angeben." }

            val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
            val uri = try {
                URI(withScheme)
            } catch (_: Exception) {
                throw IllegalArgumentException("\"$input\" ist keine gültige Serveradresse.")
            }
            val host = uri.host ?: throw IllegalArgumentException("\"$input\" ist keine gültige Serveradresse.")

            val secure = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true)
            val authority = "$host:${if (uri.port != -1) uri.port else DEFAULT_PORT}"

            return ServerBases(
                httpBase = "${if (secure) "https" else "http"}://$authority",
                wsBase = "${if (secure) "wss" else "ws"}://$authority",
            )
        }
    }
}

private fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8)
