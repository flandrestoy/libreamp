package dev.libreamp.player.ui.nowplaying

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.libreamp.player.MainActivity
import dev.libreamp.player.R
import dev.libreamp.player.databinding.FragmentNowPlayingBinding
import dev.libreamp.player.playback.PlaybackController
import dev.libreamp.player.playback.PlaybackService
import dev.libreamp.player.playback.RepeatMode
import dev.libreamp.player.playback.SpectrumAnalyzer
import dev.libreamp.player.ui.widget.HatchDrawable
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private var userSeeking = false
    private var displayedArtPath: String? = null

    /** Reused across frames; the spectrum view polls this once per drawn frame. */
    private val spectrumBands = FloatArray(SpectrumAnalyzer.BAND_COUNT)

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

        binding.buttonEffects.setOnClickListener { host()?.openEffects() }
        binding.indicatorPlaylist.setOnClickListener { host()?.showPlaylist() }

        binding.imageAlbumArt.setImageDrawable(HatchDrawable(requireContext()))
        binding.spectrum.provider = { bands -> engine.readSpectrum(bands) }

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
                        .filter { it.isNotBlank() }.joinToString(" — ")

                    bindFormatChip(entry?.displayName)

                    binding.buttonPlayPause.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    binding.buttonPlayPause.contentDescription =
                        getString(if (state.isPlaying) R.string.action_pause else R.string.action_play)

                    binding.buttonRepeat.isActivated = state.repeatMode != RepeatMode.OFF
                    binding.iconRepeat.isActivated = state.repeatMode != RepeatMode.OFF
                    binding.textRepeatBadge.text =
                        if (state.repeatMode == RepeatMode.ONE) getString(R.string.repeat_one_badge) else ""
                    binding.buttonShuffle.isActivated = state.shuffle
                    // The spectrum's frame loop idles out when the bars settle;
                    // playback resuming is the signal to start it again.
                    if (state.isPlaying) binding.spectrum.resume()

                    bindArt(entry?.artPath)

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

    /**
     * The container format comes off the file name.
     *
     * The design pairs it with a sample-rate/bit-depth chip, which nothing in
     * this app currently knows: the entity stores no stream properties, and the
     * JNI surface exposes duration, tags and cover art but no stream metadata.
     * Rather than print a guess next to a real value, that chip stays hidden
     * until the native bridge can answer for it.
     */
    private fun bindFormatChip(displayName: String?) {
        val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        binding.textFormat.isVisible = extension != null
        binding.textFormat.text = extension?.uppercase(Locale.US).orEmpty()
        binding.textRate.isVisible = false
    }

    private fun bindArt(artPath: String?) {
        if (artPath == displayedArtPath) return
        displayedArtPath = artPath
        val bitmap = artPath?.let { BitmapFactory.decodeFile(it) }
        if (bitmap != null) {
            binding.imageAlbumArt.setImageBitmap(bitmap)
            binding.textNoCover.isVisible = false
        } else {
            binding.imageAlbumArt.setImageDrawable(HatchDrawable(requireContext()))
            binding.textNoCover.isVisible = true
        }
    }

    private fun host(): MainActivity? = activity as? MainActivity

    private fun sendServiceAction(action: String) {
        val intent = Intent(requireContext(), PlaybackService::class.java).setAction(action)
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    override fun onDestroyView() {
        binding.spectrum.provider = null
        super.onDestroyView()
        _binding = null
    }
}
