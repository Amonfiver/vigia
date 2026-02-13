package com.minda.vigilante.telegram

import okhttp3.*
import okhttp3.FormBody
import java.io.IOException

class TelegramClient(
    private val botToken: String,
    private val chatId: String
) {
    private val http = OkHttpClient()

    fun send(text: String, onResult: (success: Boolean, message: String?) -> Unit) {
        if (botToken.isBlank() || chatId.isBlank()) {
            onResult(false, "Token o ChatId vacío")
            return
        }

        val url = "https://api.telegram.org/bot$botToken/sendMessage"

        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()

        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()

        http.newCall(req).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, "HTTP ${response.code}")
                }
                response.close()
            }
        })
    }
}
