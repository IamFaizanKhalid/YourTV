package com.iamfaizankhalid.YourTV

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlin.math.roundToInt

/**
 * Publishes the app's live channels to the Android TV home screen.
 *
 * The home screen does not let apps reorder channels, so the app publishes a single
 * "YourTV" channel. Program weights are used to order the cards inside that channel.
 */
class HomeScreenChannels(context: Context) {
    private companion object {
        const val TAG = "HomeScreenChannels"
        const val CHANNEL_INTERNAL_ID = "yourtv_home"
        const val LOGO_SIZE_DP = 80
        val LOCK = Any()
    }

    private val appContext = context.applicationContext
    private val helper = PreviewChannelHelper(appContext)
    private val viewStats = ChannelViewStats(appContext)
    private val streamRepository = LiveStreamRepository(appContext)

    fun sync() {
        synchronized(LOCK) {
            try {
                val channelId = getOrCreateChannel() ?: return
                val existingPrograms = existingPrograms(channelId)
                val rankedChannels = viewStats.rankedChannels(Channels.all)
                val channelCount = rankedChannels.size

                rankedChannels.forEachIndexed { rank, channel ->
                    val existingProgramId = existingPrograms[channel.youtubeUrl]
                    val existingProgram = existingProgramId?.let(helper::getPreviewProgram)
                    val builder = existingProgram
                        ?.let { PreviewProgram.Builder(it) }
                        ?: PreviewProgram.Builder()
                    val posterArt = posterArt(channel)

                    builder
                        .setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                        .setTitle(channel.name)
                        //.setDescription(description(channel))
                        .setPosterArtUri(posterArt.uri)
                        .setPosterArtAspectRatio(posterArt.aspectRatio)
                        .setIntent(channelIntent(channel))
                        .setInternalProviderId(channel.youtubeUrl)
                        .setWeight(channelCount - rank)

                    val program = builder.build()
                    if (existingProgram != null && existingProgramId != null) {
                        helper.updatePreviewProgram(existingProgramId, program)
                    } else {
                        helper.publishPreviewProgram(program)
                    }
                }

                val activeChannels = Channels.all.mapTo(HashSet()) { it.youtubeUrl }
                existingPrograms
                    .filterKeys { it !in activeChannels }
                    .values
                    .forEach(helper::deletePreviewProgram)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to update Android TV home screen channels", error)
            }
        }
    }

    private fun getOrCreateChannel(): Long? {
        helper.getAllChannels()
            .firstOrNull { it.internalProviderId == CHANNEL_INTERNAL_ID }
            ?.let { return it.id }

        val builder = PreviewChannel.Builder()
            .setDisplayName(appContext.getString(R.string.app_name) + " — Most watched")
            .setDescription("Most watched news channels")
            .setInternalProviderId(CHANNEL_INTERNAL_ID)
            .setAppLinkIntent(
                Intent(appContext, ChannelGridActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

        channelLogo()?.let(builder::setLogo)
        return helper.publishDefaultChannel(builder.build())
    }

    private fun existingPrograms(channelId: Long): Map<String, Long> {
        val programs = mutableMapOf<String, Long>()
        val projection = arrayOf(
            TvContractCompat.BaseTvColumns._ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )

        appContext.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(TvContractCompat.BaseTvColumns._ID)
            val internalIdColumn = cursor.getColumnIndexOrThrow(
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            )
            while (cursor.moveToNext()) {
                val internalId = cursor.getString(internalIdColumn) ?: continue
                programs[internalId] = cursor.getLong(idColumn)
            }
        }

        return programs
    }

    private fun description(channel: Channel): String {
        val weekly = viewStats.weeklyViews(channel)
        val total = viewStats.totalViews(channel)
        return if (weekly == 0 && total == 0) {
            channel.group
        } else {
            "${channel.group} • $weekly this week • $total total"
        }
    }

    private data class PosterArt(val uri: Uri, val aspectRatio: Int)

    private fun posterArt(channel: Channel): PosterArt {
        val remoteIcon = streamRepository.cachedIconUrl(channel)
        return if (!remoteIcon.isNullOrBlank()) {
            PosterArt(
                Uri.parse(remoteIcon),
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
            )
        } else {
            PosterArt(
                Uri.parse(
                    "android.resource://${appContext.packageName}/drawable/poster_fallback"
                ),
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            )
        }
    }

    private fun channelIntent(channel: Channel): Intent =
        Intent(appContext, ChannelGridActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(ChannelGridActivity.EXTRA_OPEN_CHANNEL_INDEX, Channels.all.indexOf(channel))
        }

    private fun channelLogo(): Bitmap? {
        val drawable = AppCompatResources.getDrawable(appContext, R.drawable.ic_launcher)
            ?: return null
        val size = (LOGO_SIZE_DP * appContext.resources.displayMetrics.density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(appContext.getColor(R.color.background))
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }
}
