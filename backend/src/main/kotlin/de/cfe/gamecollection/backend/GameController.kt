package de.cfe.gamecollection.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class Game(val id: Long, val title: String)

@RestController
class GameController {

    @GetMapping("/api/games")
    fun games(): List<Game> = listOf(
        Game(1, "Chess"),
        Game(2, "Go"),
        Game(3, "Backgammon"),
    )
}
