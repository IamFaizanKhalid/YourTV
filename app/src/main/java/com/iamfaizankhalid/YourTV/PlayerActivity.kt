package com.iamfaizankhalid.YourTV

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.iamfaizankhalid.YourTV.databinding.ActivityPlayerBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val TAG = "YourTVPlayer"
        private const val OVERLAY_TIMEOUT_MS = 3500L
        private const val STALE_STREAM_TIMEOUT_MS = 20_000L
        private const val CHANNEL_NUMBER_TIMEOUT_MS = 900L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var youTubePlayer: YouTubePlayer? = null
    private lateinit var streamRepository: LiveStreamRepository
    private val viewStats by lazy { ChannelViewStats(this) }
    private val homeScreenChannels by lazy { HomeScreenChannels(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        binding.playerOverlay.visibility = View.GONE
    }

    private var channelIndex = 0
    private var currentChannel: Channel? = null
    private var loadGeneration = 0
    private var playingCachedStream = false
    private var retryingStream = false
    private var playerState = PlayerConstants.PlayerState.UNKNOWN
    private var staleStreamCheck: Runnable? = null
    private var currentVideoId: String? = null
    private var cachedFetchedAt = 0L
    private var dailyRefreshStarted = false
    private var reloadOnResume = false
    private var digitBuffer = ""

    private val channelNumberTimeout = Runnable {
        val number = digitBuffer.toIntOrNull()?.minus(1) ?: -1
        if (number in Channels.all.indices) changeChannelTo(number)
        digitBuffer = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamRepository = LiveStreamRepository(this)

        val requestedIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)
        channelIndex = if (requestedIndex in Channels.all.indices) {
            requestedIndex
        } else {
            LastChannel.get(this)
        }
        LastChannel.set(this, channelIndex)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post { hideSystemBars() }

        val playerListener = object : AbstractYouTubePlayerListener() {
            override fun onReady(player: YouTubePlayer) {
                youTubePlayer = player
                loadCurrentChannel()
            }

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

        binding.youtubePlayerView.let { playerView ->
            lifecycle.addObserver(playerView)

            // Hide the player UI and keep YouTube from offering related videos,
            // annotations or captions. Privacy-enhanced mode uses youtube-nocookie.com.
            val options = IFramePlayerOptions.Builder(this)
                .controls(0)
                .rel(0)
                .ivLoadPolicy(3)
                .ccLoadPolicy(0)
                .privacyEnhancedMode(true)
                .build()

            playerView.initialize(playerListener, true, options)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val requestedIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)
        if (requestedIndex in Channels.all.indices && requestedIndex != channelIndex) {
            changeChannelTo(requestedIndex)
        }
    }

    override fun onResume() {
        super.onResume()

        // The grid brings this activity back to the front instead of creating a new
        // instance, so pick up a channel selected there.
        val selectedIndex = LastChannel.get(this)
        if (selectedIndex != channelIndex) {
            changeChannelTo(selectedIndex)
        } else if (reloadOnResume) {
            // The iframe pauses when the activity stops and can come back with a
            // black surface, so restart the live stream instead of just resuming it.
            loadCurrentChannel(recordView = false)
        } else if (currentVideoId != null &&
            playerState == PlayerConstants.PlayerState.PAUSED
        ) {
            youTubePlayer?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        if (currentVideoId != null) {
            reloadOnResume = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(channelNumberTimeout)
        cancelStaleStreamCheck()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadCurrentChannel(recordView: Boolean = true) {
        val channel = Channels.all[channelIndex]
        currentChannel = channel
        loadGeneration++
        val generation = loadGeneration
        playingCachedStream = false
        retryingStream = false
        playerState = PlayerConstants.PlayerState.UNKNOWN
        currentVideoId = null
        cachedFetchedAt = 0L
        dailyRefreshStarted = false
        reloadOnResume = false
        digitBuffer = ""
        handler.removeCallbacks(channelNumberTimeout)
        cancelStaleStreamCheck()

        setOverlayIcon(null)
        showOverlay("${channelIndex + 1}  ${channel.name}")
        Log.d(TAG, "Loading ${channel.name}")

        if (recordView) {
            viewStats.recordView(channel)
            lifecycleScope.launch(Dispatchers.IO) { homeScreenChannels.sync() }
        }

        lifecycleScope.launch {
            // Live URLs rarely change: play the last known one immediately and only
            // look up a fresh URL if it turns out to be dead. If the cached id is
            // from a previous day, it is re-checked during playback.
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
        lifecycleScope.launch {
            try {
                val stream = streamRepository.resolve(channel, forceRefresh = true)
                    ?: throw IllegalStateException(getString(R.string.stream_unavailable))

                if (generation != loadGeneration) return@launch
                startPlayback(channel, stream, fromCache = false, generation)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to load ${channel.name}", error)
                if (generation == loadGeneration) showError(error)
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
            lifecycleScope.launch {
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

        lifecycleScope.launch {
            try {
                val latest = streamRepository.refreshVideoId(channel) ?: return@launch
                if (generation != loadGeneration) return@launch

                if (latest.videoId == videoId) {
                    cachedFetchedAt = latest.fetchedAt
                    Log.d(TAG, "Cached stream for ${channel.name} is unchanged")
                } else {
                    Log.d(TAG, "Cached stream for ${channel.name} changed, loading the new one")
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        startPlayback(channel, latest, fromCache = false, generation)
                    }
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
        val channel = currentChannel ?: return
        if (retryingStream) return

        if (!playingCachedStream) {
            showError(IllegalStateException(getString(R.string.stream_unavailable)))
            return
        }

        retryingStream = true
        cancelStaleStreamCheck()
        val generation = loadGeneration

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Refreshing stale stream for ${channel.name}")
                val stream = streamRepository.resolve(channel, forceRefresh = true)
                    ?: throw IllegalStateException(getString(R.string.stream_unavailable))

                if (generation != loadGeneration) return@launch
                startPlayback(channel, stream, fromCache = false, generation)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to refresh stream for ${channel.name}", error)
                if (generation == loadGeneration) showError(error)
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

    private fun showError(error: Throwable) {
        Toast.makeText(
            this,
            error.message ?: getString(R.string.stream_unavailable),
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    openChannelGrid()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    changeChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    changeChannel(1)
                    return true
                }
                else -> {
                    val number = event.keyCode - KeyEvent.KEYCODE_0
                    val numberPad = event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                    val digit = when {
                        number in 0..9 -> number
                        numberPad in 0..9 -> numberPad
                        else -> return super.dispatchKeyEvent(event)
                    }
                    onChannelNumber(digit.toString())
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun changeChannel(delta: Int) {
        changeChannelTo((channelIndex + delta).mod(Channels.all.size))
    }

    private fun changeChannelTo(index: Int) {
        if (index !in Channels.all.indices) return
        channelIndex = index
        LastChannel.set(this, index)
        loadCurrentChannel()
    }

    private fun onChannelNumber(digit: String) {
        digitBuffer += digit
        handler.removeCallbacks(channelNumberTimeout)
        handler.postDelayed(channelNumberTimeout, CHANNEL_NUMBER_TIMEOUT_MS)
    }

    private fun openChannelGrid() {
        startActivity(
            Intent(this, ChannelGridActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
}