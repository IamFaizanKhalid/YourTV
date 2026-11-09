package com.iamfaizankhalid.YourTV

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iamfaizankhalid.YourTV.databinding.ActivityChannelGridBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ChannelGridActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_CHANNEL_INDEX = "open_channel_index"
    }

    private lateinit var binding: ActivityChannelGridBinding
    private lateinit var internationalAdapter: ChannelAdapter
    private lateinit var pakistaniAdapter: ChannelAdapter
    private val streamRepository by lazy { LiveStreamRepository(this) }
    private val homeScreenChannels by lazy { HomeScreenChannels(this) }
    private val iconLoadSemaphore = Semaphore(3)
    private var digitBuffer = ""
    private val digitTimeout = Runnable {
        playNumber(digitBuffer)
        digitBuffer = ""
    }
    private val handler = Handler(Looper.getMainLooper())
    private var pendingOpenChannelIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        internationalAdapter = ChannelAdapter(channelsIn("International")) { index ->
            openPlayer(index)
        }
        pakistaniAdapter = ChannelAdapter(channelsIn("Pakistan")) { index ->
            openPlayer(index)
        }

        binding.internationalRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.internationalRow.adapter = internationalAdapter
        binding.pakistaniRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pakistaniRow.adapter = pakistaniAdapter

        binding.internationalRow.post {
            binding.internationalRow.getChildAt(0)?.requestFocus()
        }

        pendingOpenChannelIndex = openChannelIndexFrom(intent)
        handlePendingOpenChannel()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { homeScreenChannels.sync() }

            coroutineScope {
                Channels.all.forEachIndexed { index, channel ->
                    launch {
                        iconLoadSemaphore.withPermit {
                            streamRepository.channelIcon(channel)?.let { icon ->
                                internationalAdapter.setChannelIcon(index, icon)
                                pakistaniAdapter.setChannelIcon(index, icon)
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.IO) { homeScreenChannels.sync() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenChannelIndex = openChannelIndexFrom(intent)
        handlePendingOpenChannel()
    }

    override fun onResume() {
        super.onResume()
        handlePendingOpenChannel()
    }

    private fun channelsIn(group: String): List<ChannelAdapter.Entry> =
        Channels.all.mapIndexedNotNull { index, channel ->
            if (channel.group == group) ChannelAdapter.Entry(channel, index) else null
        }

    private fun openChannelIndexFrom(intent: Intent?): Int? {
        if (intent == null || !intent.hasExtra(EXTRA_OPEN_CHANNEL_INDEX)) return null
        return intent.getIntExtra(EXTRA_OPEN_CHANNEL_INDEX, -1)
            .takeIf { it in Channels.all.indices }
    }

    private fun handlePendingOpenChannel() {
        val index = pendingOpenChannelIndex ?: return
        pendingOpenChannelIndex = null
        // Let the rows finish laying out before launching the player.
        binding.root.post { openPlayer(index) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val number = event.keyCode - KeyEvent.KEYCODE_0
            val numberPad = event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
            when {
                number in 0..9 -> appendDigit(number.toString())
                numberPad in 0..9 -> appendDigit(numberPad.toString())
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun appendDigit(digit: String) {
        digitBuffer += digit
        handler.removeCallbacks(digitTimeout)
        handler.postDelayed(digitTimeout, 900)
    }

    private fun playNumber(buffer: String) {
        val number = buffer.toIntOrNull()?.minus(1) ?: return
        if (number in Channels.all.indices) openPlayer(number)
    }

    private fun openPlayer(index: Int) {
        handler.removeCallbacks(digitTimeout)
        digitBuffer = ""
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, index)
        })
    }
}
