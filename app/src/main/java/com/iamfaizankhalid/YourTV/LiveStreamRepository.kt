package com.iamfaizankhalid.YourTV

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChannelStream(
    val videoId: String,
    val icon: Bitmap?
)

class LiveStreamRepository(context: Context) {
    companion object {
        private const val TAG = "YouTVPlayer"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private const val VIDEO_CACHE_MS = 6 * 60 * 60 * 1000L
        private const val ICON_CACHE_MS = 30L * 24 * 60 * 60 * 1000L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("live_stream_cache", Context.MODE_PRIVATE)
    private val iconDir = File(appContext.filesDir, "channel_icons")
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val avatarUrlRegex = Regex("""https://yt3\.ggpht\.com/[^\s"'<>]+""")
    private val avatarSizeRegex = Regex("""=s(\d+)""")
    private val hexEscapeRegex = Regex("""\\x([0-9a-fA-F]{2})""")
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")

    suspend fun resolve(channel: Channel, forceRefresh: Boolean = false): ChannelStream? =
        withContext(Dispatchers.IO) {
            val cachedVideoId = prefs.getString(channel.youtubeUrl, null)
            val videoIsFresh =
                !forceRefresh && System.currentTimeMillis() - prefs.getLong(
                    videoTimeKey(channel),
                    0L
                ) < VIDEO_CACHE_MS
            val page = if (forceRefresh || !videoIsFresh || !hasFreshIcon(channel)) {
                fetchChannelPage(channel)
            } else {
                null
            }

            page?.videoId?.let { saveVideoId(channel, it) }

            val videoId = page?.videoId ?: cachedVideoId ?: return@withContext null
            val iconFile = if (page?.iconUrl != null) {
                updateCachedIcon(channel, page.iconUrl, forceRefresh)
            } else {
                cachedIconFile(channel)
            }

            ChannelStream(videoId, iconFile?.let(::decodeIcon))
        }

    suspend fun channelIcon(channel: Channel, forceRefresh: Boolean = false): Bitmap? =
        withContext(Dispatchers.IO) {
            val cachedIcon = cachedIconFile(channel)
            if (!forceRefresh && hasFreshIcon(channel)) {
                return@withContext cachedIcon?.let(::decodeIcon)
            }

            val page = fetchChannelPage(channel)
            page?.videoId?.let { saveVideoId(channel, it) }

            val iconUrl = page?.iconUrl ?: return@withContext cachedIcon?.let(::decodeIcon)
            updateCachedIcon(channel, iconUrl, forceRefresh)?.let(::decodeIcon)
        }

    private fun fetchChannelPage(channel: Channel): ChannelPage? {
        val url = "${channel.youtubeUrl.trimEnd('/')}/live"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val html = response.body?.string().orEmpty()
                return ChannelPage(
                    videoId = videoIdFromHtml(html),
                    iconUrl = iconUrlFromHtml(html)
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Channel page lookup failed for $url", error)
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

    private fun iconUrlFromHtml(html: String): String? {
        videoOwnerAvatarUrl(html, "videoOwnerRenderer")?.let { return it }
        videoOwnerAvatarUrl(html, "slimOwnerRenderer")?.let { return it }
        return channelBarAvatarUrl(html)
    }

    private fun videoOwnerAvatarUrl(html: String, marker: String): String? {
        var searchFrom = 0
        while (true) {
            val index = html.indexOf(marker, searchFrom)
            if (index < 0) return null

            val chunk = decodeEscapes(
                html.substring(index, minOf(html.length, index + 4000))
            )
            val thumbnailBlock = chunk
                .substringAfter("\"thumbnail\"", chunk)
                .substringBefore("\"navigationEndpoint\"")
            largestAvatarUrl(thumbnailBlock)?.let { return it }
            searchFrom = index + 1
        }
    }

    private fun channelBarAvatarUrl(html: String): String? {
        var searchFrom = 0
        while (true) {
            val index = html.indexOf("channelBarHeaderViewModel", searchFrom)
            if (index < 0) return null

            val chunk = decodeEscapes(
                html.substring(index, minOf(html.length, index + 6000))
            )
            avatarUrlRegex.find(chunk)?.value?.let { return it }
            searchFrom = index + 1
        }
    }

    private fun largestAvatarUrl(value: String): String? =
        avatarUrlRegex.findAll(value)
            .map { it.value }
            .maxByOrNull { url ->
                avatarSizeRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }

    private fun decodeEscapes(value: String): String {
        var decoded = value.replace("\\/", "/").replace("\\\"", "\"")
        decoded = hexEscapeRegex.replace(decoded) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        decoded = unicodeEscapeRegex.replace(decoded) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return decoded.replace("&amp;", "&")
    }

    private fun hasFreshIcon(channel: Channel): Boolean =
        cachedIconFile(channel) != null &&
            System.currentTimeMillis() - prefs.getLong(iconTimeKey(channel), 0L) < ICON_CACHE_MS

    private fun updateCachedIcon(
        channel: Channel,
        iconUrl: String,
        forceRefresh: Boolean
    ): File? {
        val destination = iconFile(channel)
        if (!forceRefresh && destination.exists() &&
            System.currentTimeMillis() - prefs.getLong(iconTimeKey(channel), 0L) < ICON_CACHE_MS
        ) {
            return destination
        }

        if (downloadIcon(iconUrl, destination)) {
            prefs.edit().putLong(iconTimeKey(channel), System.currentTimeMillis()).apply()
            return destination
        }

        return destination.takeIf { it.exists() }
    }

    private fun downloadIcon(url: String, destination: File): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return false

                destination.parentFile?.mkdirs()
                val temporary = File(destination.parentFile, "${destination.name}.tmp")
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (BitmapFactory.decodeFile(temporary.absolutePath) == null) {
                    temporary.delete()
                    return false
                }

                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                return true
            }
        } catch (error: Exception) {
            Log.e(TAG, "Channel icon download failed for $url", error)
        }

        return false
    }

    private fun decodeIcon(file: File): Bitmap? =
        BitmapFactory.decodeFile(file.absolutePath)

    private fun saveVideoId(channel: Channel, videoId: String) {
        prefs.edit()
            .putString(channel.youtubeUrl, videoId)
            .putLong(videoTimeKey(channel), System.currentTimeMillis())
            .apply()
    }

    private fun iconFile(channel: Channel): File {
        val name = UUID.nameUUIDFromBytes(channel.youtubeUrl.toByteArray()).toString()
        return File(iconDir, "$name.img")
    }

    private fun cachedIconFile(channel: Channel): File? =
        iconFile(channel).takeIf { it.exists() }

    private fun videoTimeKey(channel: Channel): String = "${channel.youtubeUrl}_time"

    private fun iconTimeKey(channel: Channel): String = "${channel.youtubeUrl}_icon_time"

    private data class ChannelPage(
        val videoId: String?,
        val iconUrl: String?
    )
}
