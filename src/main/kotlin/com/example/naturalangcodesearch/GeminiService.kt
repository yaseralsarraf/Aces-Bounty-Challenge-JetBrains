package com.example.naturalangcodesearch

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
//nigger
class GeminiService {
    // -----------------------------------------------------------------------
    // TODO: Ensure your API Key is correct here
    // -----------------------------------------------------------------------
    private val apiKey = "AIzaSyBFaaLcPZb89jMUoIcNHufBmxiBdyJZG4A"

    // Increased timeout to 60 seconds to prevent "Timeouts" on large code files
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun sendPrompt(prompt: String): String {
        // Using the robust "Preview" model
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=$apiKey"

        // --- THE FIX: Safe JSON Generation ---
        // We use a Map to structure the data, then let Gson convert it to a string.
        // This handles all quotes (""), newlines (\n), and special characters automatically.
        val payload = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            )
        )
        val requestBodyString = gson.toJson(payload)
        // -------------------------------------

        val request = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return "Error ${response.code}: $body"
                }

                return extractText(body)
            }
        } catch (e: Exception) {
            return "Connection Error: ${e.message}"
        }
    }

    private fun extractText(json: String): String {
        return try {
            val jsonObject = gson.fromJson(json, JsonObject::class.java)
            jsonObject.getAsJsonArray("candidates")
                .get(0).asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).asJsonObject
                .get("text").asString
        } catch (e: Exception) {
            "Error parsing JSON: ${e.message}"
        }
    }
}