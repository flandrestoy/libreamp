package dev.libreamp.player

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import dev.libreamp.player.databinding.ActivityMainBinding
import dev.libreamp.player.ui.effects.EffectsFragment
import dev.libreamp.player.ui.nowplaying.NowPlayingFragment
import dev.libreamp.player.ui.playlist.PlaylistFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = PAGES.size
            override fun createFragment(position: Int): Fragment = PAGES[position].invoke()
        }
    }

    companion object {
        private val PAGES: List<() -> Fragment> = listOf(
            { NowPlayingFragment() },
            { PlaylistFragment() },
            { EffectsFragment() }
        )
    }
}
