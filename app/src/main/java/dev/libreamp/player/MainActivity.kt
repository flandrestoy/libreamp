package dev.libreamp.player

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.libreamp.player.databinding.ActivityMainBinding
import dev.libreamp.player.ui.nowplaying.NowPlayingFragment
import dev.libreamp.player.ui.playlist.PlaylistFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(NowPlayingFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_now_playing -> { showFragment(NowPlayingFragment()); true }
                R.id.nav_playlist -> { showFragment(PlaylistFragment()); true }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment)
        }
    }
}
