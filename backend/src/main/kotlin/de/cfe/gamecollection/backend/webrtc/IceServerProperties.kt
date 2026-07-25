package de.cfe.gamecollection.backend.webrtc

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "webrtc")
class IceServerProperties {
    var iceServers: List<IceServer> = emptyList()
}

data class IceServer(
    val urls: String = "",
    val username: String? = null,
    val credential: String? = null,
)
