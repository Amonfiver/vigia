package com.minda.vigilante.vision

enum class TransferState {
    OK,
    OBSTACLE_ORANGE,
    RED_BARS_PENDING,
    MALFUNCTION
}

data class Detection(
    val state: TransferState,
    val orangeRatio: Float = 0f,
    val redRatio: Float = 0f,
    val redBars: Boolean = false,
    val debug: String = ""
)
