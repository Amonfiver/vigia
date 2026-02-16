package com.minda.vigilante.vision

data class Features(
    val orangeRatio: Float,
    val topRedRatio: Float,
    val bottomRedRatio: Float
)

enum class TrainLabel { OK, OBSTACLE, FAULT }
