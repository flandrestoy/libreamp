package dev.libreamp.player

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.viewpager2.adapter.FragmentStateAdapter
import dev.libreamp.player.databinding.ActivityMainBinding
import dev.libreamp.player.ui.effects.EffectsFragment
import dev.libreamp.player.ui.nowplaying.NowPlayingFragment
import dev.libreamp.player.ui.playlist.PlaylistFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Enabled only while the overlay is up, so back behaves normally otherwise. */
    private val overlayBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeOverlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = PAGES.size
            override fun createFragment(position: Int): Fragment = PAGES[position].invoke()
        }

        // The overlay is a plain container rather than a back-stack entry, so
        // its dismissal has to be claimed here or back would leave the app with
        // Effects still on screen.
        onBackPressedDispatcher.addCallback(this, overlayBackCallback)
    }

    fun showPlaylist() {
        binding.viewPager.currentItem = PAGE_PLAYLIST
    }

    fun showNowPlaying() {
        binding.viewPager.currentItem = PAGE_NOW_PLAYING
    }

    fun openEffects() {
        if (binding.overlayContainer.isVisible) return
        supportFragmentManager.commit {
            replace(R.id.overlay_container, EffectsFragment())
        }
        binding.overlayContainer.isVisible = true
        // A pager swipe underneath an open overlay would move a screen the user
        // can't see, and land them somewhere unexpected on dismissal.
        binding.viewPager.isUserInputEnabled = false
        overlayBackCallback.isEnabled =true
    }

    fun closeOverlay() {
        if (!binding.overlayContainer.isVisible) return
        supportFragmentManager.findFragmentById(R.id.overlay_container)?.let { fragment ->
            supportFragmentManager.commit { remove(fragment) }
        }
        binding.overlayContainer.isVisible = false
        binding.viewPager.isUserInputEnabled = true
        overlayBackCallback.isEnabled =false
    }

    companion object {
        private const val PAGE_NOW_PLAYING = 0
        private const val PAGE_PLAYLIST = 1

        private val PAGES: List<() -> Fragment> = listOf(
            { NowPlayingFragment() },
            { PlaylistFragment() }
        )
    }
}
