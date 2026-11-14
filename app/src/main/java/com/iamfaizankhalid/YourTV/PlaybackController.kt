package com.iamfaizankhalid.YourTV

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iamfaizankhalid.YourTV.databinding.ActivityChannelGridBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerCallback
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Owns the YouTube player that lives on the main screen. The player is created and
 * initialized with the grid, without a video, so selecting a channel only has to
 * hand it a video id instead of waiting for the iframe to boot.
 */
class PlaybackController(
    private val activity: AppCompatActivity,
    private val binding: ActivityChannelGridBinding,
    private val onExitPlayback: () -> Unit
) {
    companion object {
        private const val TAG = "YourTVPlayer"
        private const val OVERLAY_TIMEOUT_MS = 3500L
        private const val STALE_STREAM_TIMEOUT_MS = 20_000L
        private const val CHANNEL_NUMBER_TIMEOUT_MS = 900L
    }

    private val streamRepository by lazy { LiveStreamRepository(activity) }
    private val viewStats by lazy { ChannelViewStats(activity) }
    private val homeScreenChannels by lazy { HomeScreenChannels(activity) }

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        binding.playerOverlay.visibility = View.GONE
    }

    private var youTubePlayer: YouTubePlayer? = null
    private var playerReady = false
    private var channelIndex = 0
    private var currentChannel: Channel? = null
    private var pendingChannelIndex: Int? = null
    private var loadGeneration = 0
    private var playingCachedStream = false
    private var retryingStream = false
    private var playerState = PlayerConstants.PlayerState.UNKNOWN
    private var staleStreamCheck: Runnable? = null
    private var currentVideoId: String? = null
    private var cachedFetchedAt = 0L
    private var dailyRefreshStarted = false
    private var digitBuffer = ""

    private val channelNumberTimeout = Runnable {
        val number = digitBuffer.toIntOrNull()?.minus(1) ?: -1
        if (number in Channels.all.indices) {
            openChannel(number)
        }
        digitBuffer = ""
    }

    private val isPlaying: Boolean
        get() = binding.playerContainer.visibility == View.VISIBLE

    /**
     * Starts loading the iframe immediately. No video is loaded until a channel is opened.
     */
    fun initialize() {
        binding.youtubePlayerView.getYouTubePlayerWhenReady(
            object : YouTubePlayerCallback {
                override fun onYouTubePlayer(player: YouTubePlayer) {
                    playerReady = true
                    youTubePlayer = player
                    Log.d(TAG, "Player ready")

                    val pending = pendingChannelIndex ?: return
                    pendingChannelIndex = null
                    if (pending == channelIndex) {
                        startStream(Channels.all[pending], loadGeneration)
                    }
                }
            }
        )

        val playerListener = object : AbstractYouTubePlayerListener() {
            override fun onStateChange(
                youTubePlayer: YouTubePlayer,
                state: PlayerConstants.PlayerState
            ) {
                playerState = state
                Log.d(TAG, "State: $state")
                if (state == PlayerConstants.PlayerState.PLAYING) {
                    Log.d(TAG, "Playing ${currentChannel?.name}")
                    cancelStaleStreamCheck()
                    refreshCachedStreamIfStale()
                }
            }

            override fun onError(
                youTubePlayer: YouTubePlayer,
                error: PlayerConstants.PlayerError
            ) {
                Log.e(TAG, "Player error: $error")
                retryWithFreshStream()
            }
        }

        // Hide the player UI and keep YouTube from offering related videos,
        // annotations or captions. Privacy-enhanced mode uses youtube-nocookie.com.
        val options = IFramePlayerOptions.Builder(activity)
            .controls(0)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(0)
            .build()

        binding.youtubePlayerView.initialize(playerListener, true, options)
    }

    fun openChannel(index: Int) {
        if (index !in Channels.all.indices) return

        val channel = Channels.all[index]
        channelIndex = index
        currentChannel = channel
        loadGeneration++
        val generation = loadGeneration
        playingCachedStream = false
        retryingStream = false
        playerState = PlayerConstants.PlayerState.UNKNOWN
        currentVideoId = null
        cachedFetchedAt = 0L
        dailyRefreshStarted = false
        digitBuffer = ""
        handler.removeCallbacks(channelNumberTimeout)
        cancelStreamTimers()

        binding.gridContent.visibility = View.GONE
        binding.playerContainer.visibility = View.VISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setOverlayIcon(null)
        showOverlay("${index + 1}  ${channel.name}")
        Log.d(TAG, "Loading ${channel.name}")

        viewStats.recordView(channel)
        activity.lifecycleScope.launch(Dispatchers.IO) { homeScreenChannels.sync() }

        if (!playerReady) {
            // The iframe is still warming up; play as soon as it reports ready.
            pendingChannelIndex = index
            return
        }

        pendingChannelIndex = null
        startStream(channel, generation)
    }

    /** Handles player keys. Returns true when the event was consumed. */
    fun handleKeyDown(event: KeyEvent): Boolean {
        if (!isPlaying) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> changeChannel(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> changeChannel(1)
            KeyEvent.KEYCODE_BACK -> exitPlayback()
            else -> {
                val number = event.keyCode - KeyEvent.KEYCODE_0
                val numberPad = event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                val digit = when {
                    number in 0..9 -> number
                    numberPad in 0..9 -> numberPad
                    else -> return false
                }
                onChannelNumber(digit.toString())
            }
        }
        return true
    }

    fun exitPlayback() {
        if (!isPlaying) return

        loadGeneration++
        pendingChannelIndex = null
        cancelStreamTimers()
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(channelNumberTimeout)
        digitBuffer = ""

        // Pausing also clears the library's playback resumer, so returning to the
        // grid does not resume the video when the activity is stopped and resumed.
        youTubePlayer?.pause()

        playingCachedStream = false
        retryingStream = false
        playerState = PlayerConstants.PlayerState.UNKNOWN
        currentVideoId = null
        cachedFetchedAt = 0L
        dailyRefreshStarted = false
        currentChannel = null

        binding.playerOverlay.visibility = View.GONE
        binding.playerContainer.visibility = View.INVISIBLE
        binding.gridContent.visibility = View.VISIBLE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onExitPlayback()
    }

    fun release() {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(channelNumberTimeout)
        cancelStreamTimers()
    }

    private fun changeChannel(delta: Int) {
        openChannel((channelIndex + delta).mod(Channels.all.size))
    }

    private fun onChannelNumber(digit: String) {
        digitBuffer += digit
        handler.removeCallbacks(channelNumberTimeout)
        handler.postDelayed(channelNumberTimeout, CHANNEL_NUMBER_TIMEOUT_MS)
    }

    private fun startStream(channel: Channel, generation: Int) {
        activity.lifecycleScope.launch {
            // Live URLs rarely change: play the last known one immediately and only
            // look up a fresh URL if it turns out to be dead. If the cached id is
            // from a previous day, PlaybackController re-checks it during playback.
            val cached = streamRepository.cachedStream(channel)
            if (generation != loadGeneration) return@launch

            if (cached != null) {
                startPlayback(channel, cached, fromCache = true, generation)
            } else {
                fetchStreamAndPlay(channel, generation)
            }
        }
    }

    private fun fetchStreamAndPlay(channel: Channel, generation: Int) {
        activity.lifecycleScope.launch {
            try {
                val stream = streamRepository.resolve(channel, forceRefresh = true)
                    ?: throw IllegalStateException(activity.getString(R.string.stream_unavailable))

                if (generation != loadGeneration) return@launch
                startPlayback(channel, stream, fromCache = false, generation)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to load ${channel.name}", error)
                if (generation == loadGeneration && isPlaying) showError(error)
            }
        }
    }

    private fun startPlayback(
        channel: Channel,
        stream: ChannelStream,
        fromCache: Boolean,
        generation: Int
    ) {
        if (generation != loadGeneration) return

        playingCachedStream = fromCache
        currentVideoId = stream.videoId
        cachedFetchedAt = stream.fetchedAt
        dailyRefreshStarted = false
        setOverlayIcon(stream.icon)
        youTubePlayer?.loadVideo(stream.videoId, 0f)

        if (fromCache) {
            scheduleStaleStreamCheck(generation)
        } else {
            cancelStaleStreamCheck()
        }

        if (stream.icon == null) {
            activity.lifecycleScope.launch {
                val icon = streamRepository.channelIcon(channel)
                if (generation == loadGeneration) setOverlayIcon(icon)
            }
        }
    }

    /**
     * Live ids are stable, but channels do rotate them. While a cached stream is
     * playing, refresh it once a day: switch to the new id if it changed, otherwise
     * just record that the cached id was still valid today.
     */
    private fun refreshCachedStreamIfStale() {
        if (!playingCachedStream || dailyRefreshStarted) return
        if (cachedFetchedAt > 0L && isToday(cachedFetchedAt)) return

        val channel = currentChannel ?: return
        val videoId = currentVideoId ?: return
        val generation = loadGeneration
        dailyRefreshStarted = true

        activity.lifecycleScope.launch {
            try {
                val latest = streamRepository.refreshVideoId(channel) ?: return@launch
                if (generation != loadGeneration) return@launch

                if (latest.videoId == videoId) {
                    cachedFetchedAt = latest.fetchedAt
                    Log.d(TAG, "Cached stream for ${channel.name} is unchanged")
                } else {
                    Log.d(TAG, "Cached stream for ${channel.name} changed, loading the new one")
                    startPlayback(channel, latest, fromCache = false, generation)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to refresh cached stream for ${channel.name}", error)
            }
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return date == LocalDate.now(zone)
    }

    private fun retryWithFreshStream() {
        if (!isPlaying) return
        val channel = currentChannel ?: return
        if (retryingStream) return

        if (!playingCachedStream) {
            showError(IllegalStateException(activity.getString(R.string.stream_unavailable)))
            return
        }

        retryingStream = true
        cancelStaleStreamCheck()
        val generation = loadGeneration

        activity.lifecycleScope.launch {
            try {
                Log.d(TAG, "Refreshing stale stream for ${channel.name}")
                val stream = streamRepository.resolve(channel, forceRefresh = true)
                    ?: throw IllegalStateException(activity.getString(R.string.stream_unavailable))

                if (generation != loadGeneration) return@launch
                startPlayback(channel, stream, fromCache = false, generation)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to refresh stream for ${channel.name}", error)
                if (generation == loadGeneration && isPlaying) showError(error)
            }
        }
    }

    private fun scheduleStaleStreamCheck(generation: Int) {
        cancelStaleStreamCheck()
        val check = Runnable {
            if (generation != loadGeneration) return@Runnable
            // Still waiting on the iframe (not buffering or playing): the cached URL is dead.
            if (playingCachedStream &&
                playerState != PlayerConstants.PlayerState.PLAYING &&
                playerState != PlayerConstants.PlayerState.BUFFERING
            ) {
                retryWithFreshStream()
            }
        }
        staleStreamCheck = check
        handler.postDelayed(check, STALE_STREAM_TIMEOUT_MS)
    }

    private fun cancelStaleStreamCheck() {
        staleStreamCheck?.let(handler::removeCallbacks)
        staleStreamCheck = null
    }

    private fun cancelStreamTimers() {
        cancelStaleStreamCheck()
    }

    private fun showError(error: Throwable) {
        Toast.makeText(
            activity,
            error.message ?: activity.getString(R.string.stream_unavailable),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showOverlay(text: String) {
        binding.playerOverlayText.text = text
        binding.playerOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
    }

    private fun setOverlayIcon(icon: Bitmap?) {
        binding.playerOverlayIcon.setImageBitmap(icon)
    }
}