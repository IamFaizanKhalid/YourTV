package com.iamfaizankhalid.YourTV

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.iamfaizankhalid.YourTV.databinding.ActivityPlayerBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val TAG = "YourTVPlayer"
        private const val OVERLAY_TIMEOUT_MS = 3500L
        private const val STALE_STREAM_TIMEOUT_MS = 20_000L
        private const val CHANNEL_NUMBER_TIMEOUT_MS = 2_000L
        private const val SLOW_BUFFER_TIMEOUT_MS = 15_000L
        private const val COLD_START_TIMEOUT_MS = 10_000L
        private const val WAITING_RETRY_MS = 10_000L
        private const val EXIT_BACK_DELAY_MS = 1_000L
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
    private val hideStatusMessage = Runnable { hideStatus() }

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
    private var slowBufferCheck: Runnable? = null
    private var slowBufferWarningShown = false
    private var hasStartedPlayback = false
    private var switchingToLatestStream = false
    private var retryCountdown: Runnable? = null
    private var startupCheck: Runnable? = null
    private var playerInitialized = false
    private lateinit var playerListener: AbstractYouTubePlayerListener
    private var digitBuffer = ""
    private var channelListOpen = false
    private var previousChannelIndex: Int? = null
    private var channelListFocusIndex = 0
    private var ignoreOkUntil = 0L
    private var backExitArmed = false
    private var appliedMaxResolution = MaxResolution.AUTO
    private var viewportWidthPx = 0
    private var viewportHeightPx = 0
    private var appliedBandwidthLimit = BandwidthLimit.NONE
    private var throttlingProxy: ThrottlingProxy? = null
    private var proxyOverrideSet = false
    private var proxySetupInProgress = false
    private val backSwitch = Runnable {
        backExitArmed = false
        switchToLastChannel()
    }

    private val channelNumberTimeout = Runnable {
        val number = digitBuffer.toIntOrNull()?.minus(1) ?: -1
        if (number in Channels.all.indices) changeChannelTo(number)
        digitBuffer = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The OK press that launched the app from the TV launcher can arrive here too.
        ignoreOkUntil = SystemClock.uptimeMillis() + 800

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

        appliedMaxResolution = MaxResolution.get(this)
        applyMaxResolutionViewport()
        appliedBandwidthLimit = BandwidthLimit.get(this)
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateViewportScale()
        }

        playerListener = object : AbstractYouTubePlayerListener() {
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
                    hasStartedPlayback = true
                    switchingToLatestStream = false
                    hideStatus()
                    cancelStartupCheck()
                    cancelSlowBufferCheck()
                    cancelRetryCountdown()
                    cancelStaleStreamCheck()
                    slowBufferWarningShown = false
                    refreshCachedStreamIfStale()
                } else if (state == PlayerConstants.PlayerState.BUFFERING) {
                    cancelStartupCheck()
                    if (retryCountdown == null && !slowBufferWarningShown &&
                        !switchingToLatestStream
                    ) {
                        // Do not leave the user without feedback while the video buffers.
                        showStatus(
                            getString(
                                if (hasStartedPlayback) R.string.status_reconnecting
                                else R.string.status_loading
                            )
                        )
                    }
                    scheduleSlowBufferMessage()
                }
            }

            override fun onError(
                youTubePlayer: YouTubePlayer,
                error: PlayerConstants.PlayerError
            ) {
                Log.e(TAG, "Player error: $error")
                retryWithFreshStream()
            }

            override fun onPlaybackQualityChange(
                youTubePlayer: YouTubePlayer,
                playbackQuality: PlayerConstants.PlaybackQuality
            ) {
                Log.d(TAG, "Quality: $playbackQuality")
                binding.playbackResolution.text = qualityLabel(playbackQuality)
            }
        }

        binding.youtubePlayerView.let { playerView ->
            lifecycle.addObserver(playerView)
        }

        binding.channelListSettingsButton.setOnClickListener {
            closeChannelList()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateChannelScrollbar()
            }
        })

        // Always start with "Loading…"; the watchdog reports network problems.
        initializePlayerWithBandwidthLimit()
    }

    private fun initializePlayer() {
        if (playerInitialized || proxySetupInProgress) return
        playerInitialized = true

        binding.youtubePlayerView.let { playerView ->
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

        scheduleColdStartCheck()
    }

    private fun initializePlayerWithBandwidthLimit() {
        if (playerInitialized || proxySetupInProgress) return
        proxySetupInProgress = true
        applyBandwidthProxy {
            proxySetupInProgress = false
            initializePlayer()
        }
    }

    /**
     * Routes WebView traffic through the local throttling proxy. The player is
     * only initialized from [onApplied], so the first requests already use it.
     */
    private fun applyBandwidthProxy(onApplied: () -> Unit) {
        val mbps = BandwidthLimit.get(this)
        if (mbps <= 0) {
            releaseBandwidthProxy(clearOverride = true) { onApplied() }
            return
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "WebView proxy override is not supported")
            onApplied()
            return
        }

        val proxy = try {
            throttlingProxy ?: ThrottlingProxy(mbps).also {
                it.start()
                throttlingProxy = it
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start bandwidth proxy", error)
            onApplied()
            return
        }
        proxy.setBandwidthMbps(mbps)

        try {
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:${proxy.port}")
                .addDirect()
                .build()

            ProxyController.getInstance().setProxyOverride(config, mainExecutor) {
                proxyOverrideSet = true
                onApplied()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to apply WebView proxy", error)
            onApplied()
        }
    }

    private fun releaseBandwidthProxy(
        clearOverride: Boolean,
        onReleased: (() -> Unit)? = null
    ) {
        throttlingProxy?.stop()
        throttlingProxy = null

        if (clearOverride && proxyOverrideSet) {
            try {
                ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                    proxyOverrideSet = false
                    onReleased?.invoke()
                }
                return
            } catch (error: Exception) {
                Log.e(TAG, "Unable to clear WebView proxy", error)
            }
        }

        onReleased?.invoke()
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

        // Settings may have changed while this activity was stopped.
        var resolutionChanged = false
        val maxResolution = MaxResolution.get(this)
        if (maxResolution != appliedMaxResolution) {
            appliedMaxResolution = maxResolution
            applyMaxResolutionViewport()
            resolutionChanged = true
        }

        val bandwidthLimit = BandwidthLimit.get(this)
        if (bandwidthLimit != appliedBandwidthLimit) {
            appliedBandwidthLimit = bandwidthLimit
            applyBandwidthProxy {
                if (playerInitialized && youTubePlayer != null) {
                    loadCurrentChannel(recordView = false)
                }
            }
            return
        }

        if (resolutionChanged) {
            if (playerInitialized && youTubePlayer != null) {
                loadCurrentChannel(recordView = false)
            }
            return
        }

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
        if (channelListOpen) closeChannelList()
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
        cancelBackExit()
        cancelStaleStreamCheck()
        cancelSlowBufferCheck()
        cancelRetryCountdown()
        cancelStartupCheck()
        releaseBandwidthProxy(clearOverride = true)
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * YouTube sizes its quality from the player viewport. For a fixed player
     * resolution we lay the player out at that resolution's CSS size and then
     * scale the view up to fill the TV. Auto and TV use the normal full-screen
     * player.
     */
    private fun applyMaxResolutionViewport() {
        val target = MaxResolution.viewportSize(this, appliedMaxResolution)
        val playerView = binding.youtubePlayerView

        if (target == null) {
            viewportWidthPx = 0
            viewportHeightPx = 0
            playerView.scaleX = 1f
            playerView.scaleY = 1f
            playerView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            return
        }

        val density = resources.displayMetrics.density
        viewportWidthPx = (target.first * density).roundToInt()
        viewportHeightPx = (target.second * density).roundToInt()
        playerView.layoutParams = FrameLayout.LayoutParams(
            viewportWidthPx,
            viewportHeightPx,
            Gravity.CENTER
        )
        playerView.post { updateViewportScale() }
    }

    private fun updateViewportScale() {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return
        val rootWidth = binding.root.width
        val rootHeight = binding.root.height
        if (rootWidth <= 0 || rootHeight <= 0) return

        binding.youtubePlayerView.apply {
            pivotX = viewportWidthPx / 2f
            pivotY = viewportHeightPx / 2f
            scaleX = rootWidth.toFloat() / viewportWidthPx
            scaleY = rootHeight.toFloat() / viewportHeightPx
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
        slowBufferWarningShown = false
        hasStartedPlayback = false
        switchingToLatestStream = false
        currentVideoId = null
        binding.playbackResolution.text = ""
        cachedFetchedAt = 0L
        dailyRefreshStarted = false
        reloadOnResume = false
        digitBuffer = ""
        handler.removeCallbacks(channelNumberTimeout)
        cancelStaleStreamCheck()
        cancelSlowBufferCheck()
        cancelRetryCountdown()
        cancelStartupCheck()

        setOverlayIcon(null)
        showOverlay("${channelIndex + 1}  ${channel.name}")
        Log.d(TAG, "Loading ${channel.name}")

        if (!isOnline()) {
            showConnectionProblem()
            return
        }

        lifecycleScope.launch {
            // Only show "Loading…" once the internet is actually reachable;
            // otherwise keep "Waiting for the internet…" visible.
            val reachable = withContext(Dispatchers.IO) { canReachInternet() }
            if (generation != loadGeneration) return@launch
            if (!reachable) {
                showConnectionProblem()
                return@launch
            }

            showStatus(getString(R.string.status_loading))

            if (recordView) {
                viewStats.recordView(channel)
                lifecycleScope.launch(Dispatchers.IO) { homeScreenChannels.sync() }
            }

            scheduleStartupCheck(generation)

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
                if (generation == loadGeneration) showConnectionProblem()
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

        val previousVideoId = currentVideoId
        playingCachedStream = fromCache
        currentVideoId = stream.videoId
        if (previousVideoId != null && previousVideoId != stream.videoId) {
            switchingToLatestStream = true
            showStatus(getString(R.string.status_switching_to_latest_stream))
        }
        cachedFetchedAt = stream.fetchedAt
        dailyRefreshStarted = false
        retryingStream = false
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
            showConnectionProblem()
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
                if (generation == loadGeneration) showConnectionProblem()
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

    private fun showStatus(text: String) {
        handler.removeCallbacks(hideStatusMessage)
        binding.statusText.text = text
        binding.statusScrim.visibility = View.VISIBLE
        binding.statusOverlay.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        handler.removeCallbacks(hideStatusMessage)
        binding.statusScrim.visibility = View.GONE
        binding.statusOverlay.visibility = View.GONE
    }

    private fun scheduleSlowBufferMessage() {
        if (retryCountdown != null) return
        cancelSlowBufferCheck()
        val check = Runnable {
            slowBufferCheck = null
            if (retryCountdown != null) return@Runnable
            // Once the warning is shown, keep recovering even if the player
            // downgraded its state instead of staying in BUFFERING.
            val stalled = playerState == PlayerConstants.PlayerState.BUFFERING ||
                (slowBufferWarningShown && playerState != PlayerConstants.PlayerState.PLAYING)
            if (!stalled) return@Runnable
            if (!isOnline()) {
                showConnectionProblem()
                return@Runnable
            }
            // A validated Wi-Fi network can still have no working internet, so probe
            // before blaming the connection speed.
            lifecycleScope.launch {
                val reachable = withContext(Dispatchers.IO) { canReachInternet() }
                if (retryCountdown == null) {
                    val stillStalled =
                        playerState == PlayerConstants.PlayerState.BUFFERING ||
                            (slowBufferWarningShown &&
                                playerState != PlayerConstants.PlayerState.PLAYING)
                    if (stillStalled) {
                        if (reachable) {
                            if (slowBufferWarningShown) {
                                // Still stalled after the warning: the connection may
                                // have recovered on another provider without the player
                                // noticing, so restart the stream instead of staying stuck.
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    loadCurrentChannel(recordView = false)
                                }
                            } else {
                                slowBufferWarningShown = true
                                scheduleSlowBufferMessage()
                            }
                        } else {
                            showConnectionProblem()
                        }
                    }
                }
            }
        }
        slowBufferCheck = check
        handler.postDelayed(check, SLOW_BUFFER_TIMEOUT_MS)
    }

    private fun cancelSlowBufferCheck() {
        slowBufferCheck?.let(handler::removeCallbacks)
        slowBufferCheck = null
    }

    private fun showConnectionProblem() {
        switchingToLatestStream = false
        cancelStartupCheck()
        cancelSlowBufferCheck()
        showWaitingForInternet()
    }

    private fun showWaitingForInternet() {
        showStatus(getString(R.string.status_waiting_for_internet))
        cancelRetryCountdown()
        val retry = Runnable {
            retryCountdown = null
            retryCurrentChannel()
        }
        retryCountdown = retry
        handler.postDelayed(retry, WAITING_RETRY_MS)
    }

    private fun cancelRetryCountdown() {
        retryCountdown?.let(handler::removeCallbacks)
        retryCountdown = null
    }

    private fun scheduleStartupCheck(generation: Int) {
        cancelStartupCheck()
        val check = Runnable {
            startupCheck = null
            if (generation != loadGeneration) return@Runnable
            // The player never started: the iframe or the network is unreachable.
            if (playerState != PlayerConstants.PlayerState.PLAYING &&
                playerState != PlayerConstants.PlayerState.BUFFERING
            ) {
                showConnectionProblem()
            }
        }
        startupCheck = check
        handler.postDelayed(check, STALE_STREAM_TIMEOUT_MS)
    }

    private fun cancelStartupCheck() {
        startupCheck?.let(handler::removeCallbacks)
        startupCheck = null
    }

    private fun scheduleColdStartCheck() {
        cancelStartupCheck()
        val check = Runnable {
            startupCheck = null
            if (playerState != PlayerConstants.PlayerState.PLAYING &&
                playerState != PlayerConstants.PlayerState.BUFFERING
            ) {
                showConnectionProblem()
            }
        }
        startupCheck = check
        handler.postDelayed(check, COLD_START_TIMEOUT_MS)
    }

    private fun retryCurrentChannel() {
        if (!isOnline()) {
            // Keep the countdown visible instead of flashing "Loading…".
            showConnectionProblem()
            return
        }
        val generation = loadGeneration
        lifecycleScope.launch {
            // A connected network is not enough: only retry once the internet is
            // actually reachable, otherwise "Waiting for internet" would flash
            // back to "Loading…" and fail again.
            val reachable = withContext(Dispatchers.IO) { canReachInternet() }
            if (generation != loadGeneration) return@launch
            if (!reachable) {
                showConnectionProblem()
                return@launch
            }
            if (!playerInitialized) {
                // The network was down at launch, so the embedded player was never started.
                initializePlayer()
                return@launch
            }
            if (youTubePlayer == null) {
                // The player was started but never became ready; rebuild it.
                recreate()
                return@launch
            }
            // Retrying should not count as another view in the home-screen ranking.
            loadCurrentChannel(recordView = false)
        }
    }

    private fun isOnline(): Boolean = NetworkState.isOnline(this)

    private fun qualityLabel(quality: PlayerConstants.PlaybackQuality): String = when (quality) {
        PlayerConstants.PlaybackQuality.SMALL -> "240p"
        PlayerConstants.PlaybackQuality.MEDIUM -> "360p"
        PlayerConstants.PlaybackQuality.LARGE -> "480p"
        PlayerConstants.PlaybackQuality.HD720 -> "720p"
        PlayerConstants.PlaybackQuality.HD1080 -> "1080p"
        PlayerConstants.PlaybackQuality.HIGH_RES -> "1440p"
        else -> ""
    }

    /** Quick real-world check: a validated network can still have no internet. */
    private fun canReachInternet(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("www.youtube.com", 443), 5_000)
        }
        true
    } catch (error: Exception) {
        false
    }

    private fun showOverlay(text: String, showIcon: Boolean = true) {
        binding.playerOverlayText.text = text
        binding.playerOverlayIcon.visibility = if (showIcon) View.VISIBLE else View.GONE
        binding.playerOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
    }

    private fun setOverlayIcon(icon: Bitmap?) {
        binding.playerOverlayIcon.setImageBitmap(icon)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (channelListOpen) {
                    closeChannelList()
                    cancelBackExit()
                } else if (backExitArmed) {
                    // Second Back: exit without switching channels.
                    cancelBackExit()
                    finishAffinity()
                    return true
                } else {
                    // Wait briefly for a possible second Back before switching.
                    backExitArmed = true
                    handler.postDelayed(backSwitch, EXIT_BACK_DELAY_MS)
                }
                return true
            }

            if (channelListOpen) {
                if (handleChannelListNavigation(event.keyCode)) return true
                // Let the list handle OK and anything else.
                return super.dispatchKeyEvent(event)
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (SystemClock.uptimeMillis() >= ignoreOkUntil) openChannelList()
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

    private fun handleChannelListNavigation(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                moveChannelListFocus(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                moveChannelListFocus(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (currentFocus != binding.channelListSettingsButton) {
                    binding.channelListSettingsButton.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (currentFocus == binding.channelListSettingsButton) {
                    focusChannelRow(channelListFocusIndex)
                    return true
                }
            }
        }
        return false
    }

    private fun moveChannelListFocus(delta: Int) {
        if (Channels.all.isEmpty()) return
        focusChannelRow((channelListFocusIndex + delta).mod(Channels.all.size))
    }

    private fun focusChannelRow(index: Int) {
        if (index !in Channels.all.indices) return
        channelListFocusIndex = index
        binding.channelList.scrollToPosition(index)
        binding.channelList.post {
            binding.channelList.findViewHolderForAdapterPosition(index)
                ?.itemView?.requestFocus()
        }
    }

    private fun changeChannel(delta: Int) {
        changeChannelTo((channelIndex + delta).mod(Channels.all.size))
    }

    private fun changeChannelTo(index: Int) {
        if (index !in Channels.all.indices) return
        if (index == channelIndex) return
        previousChannelIndex = channelIndex
        channelIndex = index
        LastChannel.set(this, index)
        loadCurrentChannel()
    }

    private fun switchToLastChannel() {
        val last = previousChannelIndex ?: return
        // Swapping makes repeated Back presses toggle between the two channels.
        changeChannelTo(last)
    }

    private fun cancelBackExit() {
        handler.removeCallbacks(backSwitch)
        backExitArmed = false
    }

    private fun onChannelNumber(digit: String) {
        digitBuffer += digit
        // Show the digits as they are typed, like a TV channel display.
        showOverlay(digitBuffer, showIcon = false)
        handler.removeCallbacks(channelNumberTimeout)
        handler.postDelayed(channelNumberTimeout, CHANNEL_NUMBER_TIMEOUT_MS)
    }

    private fun openChannelList() {
        cancelBackExit()
        channelListOpen = true
        channelListFocusIndex = channelIndex
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = ChannelListAdapter(channelIndex) { index ->
            closeChannelList()
            changeChannelTo(index)
        }
        binding.channelListOverlay.visibility = View.VISIBLE
        binding.channelList.scrollToPosition(channelIndex)
        binding.channelList.post {
            updateChannelScrollbar()
            binding.channelList.findViewHolderForAdapterPosition(channelIndex)
                ?.itemView?.requestFocus()
                ?: binding.channelList.requestFocus()
        }
    }

    private fun updateChannelScrollbar() {
        val layoutManager = binding.channelList.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        val total = Channels.all.size
        val track = binding.channelList.height
        if (first < 0 || last < 0 || last - first + 1 >= total || track <= 0) {
            binding.channelListScrollbar.visibility = View.GONE
            return
        }

        val visible = last - first + 1
        val minThumb = (40 * resources.displayMetrics.density).toInt()
        val thumb = maxOf(minThumb, track * visible / total)
        val maxScroll = total - visible
        val offset = if (maxScroll > 0) (track - thumb) * first / maxScroll else 0

        binding.channelListScrollbar.visibility = View.VISIBLE
        binding.channelListScrollbar.layoutParams =
            binding.channelListScrollbar.layoutParams.apply { height = thumb }
        binding.channelListScrollbar.translationY = offset.toFloat()
    }

    private fun closeChannelList() {
        channelListOpen = false
        binding.channelListOverlay.visibility = View.GONE
        binding.channelList.adapter = null
    }
}