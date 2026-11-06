package com.iamfaizankhalid.YourTV

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
import androidx.lifecycle.lifecycleScope
import com.iamfaizankhalid.YourTV.databinding.ActivityPlayerBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val OVERLAY_TIMEOUT_MS = 3500L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var youTubePlayer: YouTubePlayer? = null
    private lateinit var streamRepository: LiveStreamRepository

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        binding.playerOverlay.visibility = View.GONE
    }

    private var channelIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamRepository = LiveStreamRepository(this)
        channelIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)
            .coerceIn(0, Channels.all.lastIndex)

        // Keep TV screen awake
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full-screen immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        binding.youtubePlayerView.let { playerView ->
            lifecycle.addObserver(playerView)
            playerView.addYouTubePlayerListener(
                object : AbstractYouTubePlayerListener() {
                    override fun onReady(player: YouTubePlayer) {
                        youTubePlayer = player
                        loadCurrentChannel()
                    }
                }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        overlayHandler.removeCallbacks(hideOverlay)
        super.onDestroy()
    }

    private fun loadCurrentChannel() {
        val channel = Channels.all[channelIndex]
        setOverlayIcon(null)
        showOverlay("${channelIndex + 1}  ${channel.name}")

        lifecycleScope.launch {
            try {
                val stream = streamRepository.resolve(channel, forceRefresh = false)
                    ?: throw IllegalStateException(getString(R.string.stream_unavailable))

                setOverlayIcon(stream.icon)
                youTubePlayer?.loadVideo(stream.videoId, 0f)
            } catch (error: Throwable) {
                Log.e("YouTVPlayer", "Unable to load ${channel.name}", error)
                Toast.makeText(
                    this@PlayerActivity,
                    error.message ?: getString(R.string.stream_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showOverlay(text: String) {
        binding.playerOverlayText.text = text
        binding.playerOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideOverlay)
        overlayHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
    }

    private fun setOverlayIcon(icon: Bitmap?) {
        binding.playerOverlayIcon.setImageBitmap(icon)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> changeChannel(1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> changeChannel(-1)
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
                else -> {
                    val number = event.keyCode - KeyEvent.KEYCODE_0
                    val pad = event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                    val digit = if (number in 0..9) number else if (pad in 0..9) pad else return super.dispatchKeyEvent(event)
                    onChannelNumber(digit.toString())
                    return true
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun changeChannel(delta: Int) {
        channelIndex = (channelIndex + delta).mod(Channels.all.size)
        loadCurrentChannel()
    }

    private var digitBuffer = ""
    private val channelNumberTimeout = Runnable {
        val number = digitBuffer.toIntOrNull()?.minus(1) ?: -1
        if (number in Channels.all.indices) {
            channelIndex = number
            loadCurrentChannel()
        }
        digitBuffer = ""
    }

    private fun onChannelNumber(digit: String) {
        digitBuffer += digit
        overlayHandler.removeCallbacks(channelNumberTimeout)
        overlayHandler.postDelayed(channelNumberTimeout, 900)
    }
}
