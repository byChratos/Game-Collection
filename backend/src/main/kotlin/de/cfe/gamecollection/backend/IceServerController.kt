package de.cfe.gamecollection.backend

import de.cfe.gamecollection.backend.webrtc.IceServer
import de.cfe.gamecollection.backend.webrtc.IceServerProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class IceServerController(
    private val iceServerProperties: IceServerProperties,
) {
    @GetMapping("/api/webrtc/ice-servers")
    fun iceServers(): List<IceServer> = iceServerProperties.iceServers
}
