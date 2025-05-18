package com.square.aircommand.gesture

// com.square.aircommand.gesture.GestureLabel.kt
enum class GestureLabel(val id: Int) {
    NONE(0),
    PAPER(1),
    ROCK(2),
    SCISSORS(3),
    ONE(4);

    companion object {
        fun fromId(id: Int): GestureLabel =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
