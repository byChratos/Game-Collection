package de.cfe.gamecollection.backend.model

interface GameState {
    fun isLegal(move: GameMove): Boolean
    fun apply(move: GameMove): GameState
}