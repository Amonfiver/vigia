package com.minda.vigilante.telegram

import android.util.Log
import okhttp3.*
import okhttp3.FormBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramClient(
    private val botToken: String,
    private val chatId: String
) {
    companion object {
        private const val TAG = "VIGIA_TG"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ✅ Overload: para usarlo sin callback (tu caso en Analyzer/MainActivity)
    fun send(text: String) {
        send(text) { _, _ -> /* silencioso */ }
    }

    // ✅ Versión con callback (si quieres mostrar "OK / error" en UI)
    fun send(text: String, onResult: (success: Boolean, message: String?) -> Unit) {
        if (botToken.isBlank() || chatId.isBlank()) {
            Log.e(TAG, "Token/ChatId vacío")
            onResult(false, "Token/ChatId vacío")
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

        Log.d(TAG, "Sending Telegram message... chatId=$chatId text='${text.take(80)}'")

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Telegram onFailure: ${e.message}", e)
                onResult(false, e.message ?: "IOException")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val respBody = runCatching { res.body?.string() }.getOrNull()
                    if (res.isSuccessful) {
                        Log.d(TAG, "Telegram OK (code=${res.code}) body=${respBody?.take(200)}")
                        onResult(true, null)
                    } else {
                        // IMPORTANTE: Telegram suele explicar el motivo en JSON
                        val msg = "HTTP ${res.code} body=${respBody?.take(300)}"
                        Log.e(TAG, "Telegram ERROR: $msg")
                        onResult(false, msg)
                    }
                }
            }
        })
    }
}
