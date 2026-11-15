package com.iamfaizankhalid.YourTV

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iamfaizankhalid.YourTV.databinding.ActivityChannelGridBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Secondary screen: a two row browser for the live channels. Selecting a channel
 * hands it to [PlayerActivity], which is the app's main screen.
 */
class ChannelGridActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChannelGridBinding
    private lateinit var internationalAdapter: ChannelAdapter
    private lateinit var pakistaniAdapter: ChannelAdapter
    private val streamRepository by lazy { LiveStreamRepository(this) }
    private val homeScreenChannels by lazy { HomeScreenChannels(this) }
    private val iconLoadSemaphore = Semaphore(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back from the grid leaves the app instead of bouncing to the player,
        // which would otherwise trap the user in a player <-> grid loop.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finishAffinity()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun channelsIn(group: String): List<ChannelAdapter.Entry> =
        Channels.all.mapIndexedNotNull { index, channel ->
            if (channel.group == group) ChannelAdapter.Entry(channel, index) else null
        }

    private fun openPlayer(index: Int) {
        LastChannel.set(this, index)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
}
