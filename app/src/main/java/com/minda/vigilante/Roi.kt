package com.minda.vigilante

data class Roi(
    val name: String,            // "100", "200", "300"
    val leftN: Float,            // 0..1 (normalizado)
    val topN: Float,             // 0..1
    val rightN: Float,           // 0..1
    val bottomN: Float           // 0..1
) {
    fun normalized(): Roi {
        val l = minOf(leftN, rightN).coerceIn(0f, 1f)
        val r = maxOf(leftN, rightN).coerceIn(0f, 1f)
        val t = minOf(topN, bottomN).coerceIn(0f, 1f)
        val b = maxOf(topN, bottomN).coerceIn(0f, 1f)
        return copy(leftN = l, rightN = r, topN = t, bottomN = b)
    }
}
