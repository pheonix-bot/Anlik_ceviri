package com.krunit.screentranslate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object GoogleTranslateClient {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun translateEnToTr(text: String): String = withContext(Dispatchers.IO) {
        val cleanText = text.replace("\\s+".toRegex(), " ").trim()
        if (cleanText.length < 2) return@withContext ""

        cache[cleanText]?.let { return@withContext it }

        return@withContext try {
            val encoded = URLEncoder.encode(cleanText, "UTF-8")
            val endpoint =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=$encoded"

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val rawJson = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(rawJson)
                val sentences = root.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val part = sentences.getJSONArray(i)
                    if (!part.isNull(0)) {
                        sb.append(part.getString(0))
                    }
                }
                val result = sb.toString().trim()
                if (result.isNotEmpty()) {
                    if (cache.size > 300) cache.clear()
                    cache[cleanText] = result
                }
                result
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
