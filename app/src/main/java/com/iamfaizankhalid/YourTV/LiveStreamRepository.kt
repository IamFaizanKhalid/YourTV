package com.iamfaizankhalid.YourTV

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LiveStreamRepository(context: Context) {
    private val prefs = context.getSharedPreferences("live_stream_cache", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(channel: Channel, forceRefresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val key = channel.youtubeUrl
            // channel.fixedLiveVideoId?.let { return@withContext it }

            if (!forceRefresh && System.currentTimeMillis() - prefs.getLong(
                    "${key}_time",
                    0L
                ) < 6 * 60 * 60 * 1000L
            ) {
                prefs.getString(key, null)?.let { return@withContext it }
            }

            val videoId = fetchLiveVideoId("${channel.youtubeUrl.trimEnd('/')}/live")

            if (videoId != null) {
                prefs.edit().putString(key, videoId)
                    .putLong("${key}_time", System.currentTimeMillis()).apply()
            }
            videoId
        }

    private fun fetchLiveVideoId(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val html = response.body?.string().orEmpty()
                return videoIdFromHtml(html)
            }
        } catch (error: Exception) {
            Log.e(
                "YouTVPlayer",
                "Live stream lookup failed for $url",
                error
            )
        }

        return null
    }

    private fun videoIdFromHtml(html: String): String? {
        val regex = Regex(
            """<meta\s+[^>]*property=["']og:url["'][^>]*content=["']https?://(?:www\.)?youtube\.com/watch\?v=([A-Za-z0-9_-]{11})[^"']*["']""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(html)?.groupValues?.get(1)
    }
}
