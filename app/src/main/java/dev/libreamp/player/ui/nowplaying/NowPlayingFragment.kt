package dev.libreamp.player.ui.nowplaying

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.libreamp.player.R
import dev.libreamp.player.databinding.FragmentNowPlayingBinding
import dev.libreamp.player.playback.PlaybackController
import dev.libreamp.player.playback.PlaybackService
import dev.libreamp.player.playback.RepeatMode
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private var userSeeking = false
    private var displayedArtPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val engine = PlaybackController.get(requireContext())

        binding.buttonPlayPause.setOnClickListener {
            val action = if (engine.state.value.isPlaying) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY
            sendServiceAction(action)
        }
        binding.buttonNext.setOnClickListener { sendServiceAction(PlaybackService.ACTION_NEXT) }
        binding.buttonPrev.setOnClickListener { sendServiceAction(PlaybackService.ACTION_PREV) }
        binding.buttonShuffle.setOnClickListener { engine.toggleShuffle() }
        binding.buttonRepeat.setOnClickListener { engine.cycleRepeatMode() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.textPosition.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                engine.seekTo(seekBar!!.progress * 1000L)
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { state ->
                    val entry = state.entry
                    binding.textTitle.text = entry?.title ?: entry?.displayName ?: getString(R.string.app_name)
                    binding.textSubtitle.text = listOfNotNull(entry?.artist, entry?.album)
                        .filter { it.isNotBlank() }.joinToString(" • ")

                    binding.buttonPlayPause.setImageResource(
                        if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    )
                    binding.buttonRepeat.alpha = if (state.repeatMode == RepeatMode.OFF) 0.4f else 1.0f
                    binding.buttonShuffle.alpha = if (state.shuffle) 1.0f else 0.4f

                    val artPath = entry?.artPath
                    if (artPath != displayedArtPath) {
                        displayedArtPath = artPath
                        val bmp = artPath?.let { BitmapFactory.decodeFile(it) }
                        if (bmp != null) binding.imageAlbumArt.setImageBitmap(bmp)
                        else binding.imageAlbumArt.setImageResource(R.drawable.ic_placeholder_album_art)
                    }

                    val durationMs = state.durationUs / 1000
                    binding.seekBar.max = durationMs.toInt().coerceAtLeast(0)
                    binding.textDuration.text = formatDuration(durationMs)
                    if (!userSeeking) {
                        val positionMs = state.positionUs / 1000
                        binding.seekBar.progress = positionMs.toInt().coerceAtLeast(0)
                        binding.textPosition.text = formatDuration(positionMs)
                    }
                }
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(requireContext(), PlaybackService::class.java).setAction(action)
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
