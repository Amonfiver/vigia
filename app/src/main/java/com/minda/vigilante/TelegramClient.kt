package com.minda.vigilante

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TelegramClient(
    private val token: String,
    private val chatId: String
) {
    private val http = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun send(text: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val payload = """{"chat_id":"$chatId","text":${text.jsonEscape()}}"""
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Telegram error: ${resp.code} ${resp.message}")
            }
        }
    }
}

private fun String.jsonEscape(): String {
    // devuelve una string JSON segura
    val escaped = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    return "\"$escaped\""
}
