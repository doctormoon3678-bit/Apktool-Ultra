package com.apktool.ultra.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ClaudeApiClient(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("claude_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    suspend fun modifyCode(
        code: String,
        instruction: String,
        fileType: String,
        onStream: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured) return@withContext Result.failure(Exception("API 키가 설정되지 않았습니다"))

            val systemPrompt = """You are an expert Android reverse engineering assistant integrated into Apktool Ultra.
You modify code based on user instructions. Return ONLY the modified code without explanations or markdown code blocks.
File type: $fileType"""

            val userMessage = """Here is the code to modify:

```
$code
```

Instruction: $instruction

Return only the modified code, no explanations."""

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", "claude-sonnet-4-20250514")
                put("max_tokens", 8192)
                put("system", systemPrompt)
                put("messages", messagesArray)
            }

            val url = URL("https://api.anthropic.com/v1/messages")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 60000
            connection.readTimeout = 120000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                connection.disconnect()
                return@withContext Result.failure(Exception("API 오류 (${connection.responseCode}): $error"))
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()

            val json = JSONObject(response)
            val content = json.getJSONArray("content")
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text") {
                    text.append(block.getString("text"))
                }
            }

            var result = text.toString().trim()
            if (result.startsWith("```")) {
                val firstNewline = result.indexOf('\n')
                if (firstNewline > 0) result = result.substring(firstNewline + 1)
                if (result.endsWith("```")) result = result.substring(0, result.length - 3).trimEnd()
            }

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
