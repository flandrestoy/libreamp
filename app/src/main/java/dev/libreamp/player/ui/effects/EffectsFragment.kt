package dev.libreamp.player.ui.effects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dev.libreamp.player.R
import dev.libreamp.player.data.effects.EFFECT_SPECS
import dev.libreamp.player.data.effects.EQ_BAND_COUNT
import dev.libreamp.player.data.effects.EQ_FREQUENCIES
import dev.libreamp.player.data.effects.EQ_MAX_DB
import dev.libreamp.player.data.effects.EffectSpec
import dev.libreamp.player.data.effects.EffectsConfig
import dev.libreamp.player.data.effects.EffectsStore
import dev.libreamp.player.data.effects.EqPresets
import dev.libreamp.player.data.effects.MIN_SPEED
import dev.libreamp.player.databinding.FragmentEffectsBinding
import dev.libreamp.player.databinding.ItemEffectBinding
import dev.libreamp.player.databinding.ItemEffectParamBinding
import dev.libreamp.player.databinding.ItemEqBandBinding
import dev.libreamp.player.playback.PlaybackController
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Effects screen. Every control edits one [EffectsConfig], which is persisted and
 * handed to the engine. Most of the ffmpeg half is rebuilt as a whole filter-graph
 * string, debounced so a slider drag doesn't reconfigure the graph on every pixel -
 * except the 18 eq bands, which live-update the already-running `anequalizer` stage
 * via `nativeSendFilterCommand` on every tick instead (see [EffectsFragment.pushBand]
 * and `PlaybackEngine.applyEqBand`), since a full rebuild is audible as a click.
 */
class EffectsFragment : Fragment() {

    private var _binding: FragmentEffectsBinding? = null
    private val binding get() = _binding!!

    private lateinit var bandRows: List<ItemEqBandBinding>
    private lateinit var effectRows: List<EffectRow>
    private var config = EffectsConfig()

    /** One optional filter's switch plus the sliders for its params. */
    private class EffectRow(
        val spec: EffectSpec,
        val binding: ItemEffectBinding,
        val paramRows: List<ItemEffectParamBinding>
    )

    /** Guards the listeners while controls are being populated from [config]. */
    private var updatingUi = false

    private val pushRunnable = Runnable {
        PlaybackController.get(requireContext()).applyEffects(config)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEffectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        EffectsStore.init(requireContext())
        config = EffectsStore.config.value

        bandRows = (0 until EQ_BAND_COUNT).map { index ->
            val row = ItemEqBandBinding.inflate(layoutInflater, binding.containerBands, true)
            row.textBandFreq.text = formatFrequency(EQ_FREQUENCIES[index])
            row.seekBand.setOnSeekBarChangeListener(onEqBandSeek(index, row))
            row
        }

        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            config = config.copy(enabled = checked)
            push()
        }

        binding.spinnerPreset.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, EqPresets.NAMES
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingUi) return
                val name = EqPresets.NAMES[position]
                val curve = EqPresets.curveFor(name) ?: return // "Custom" is not a curve
                config = config.copy(bandsDb = curve, preset = name)
                bindBands()
                push()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.buttonResetEq.setOnClickListener {
            config = config.copy(bandsDb = List(EQ_BAND_COUNT) { 0f }, preset = EqPresets.FLAT)
            bindBands()
            syncPresetSpinner()
            push()
        }

        binding.seekSpeed.setOnSeekBarChangeListener(onSeek { progress ->
            val speed = speedOf(progress)
            binding.textSpeedValue.text = String.format(Locale.US, "%.2fx", speed)
            config = config.copy(speed = speed)
        })
        binding.seekBalance.setOnSeekBarChangeListener(onSeek { progress ->
            val balance = balanceOf(progress)
            binding.textBalanceValue.text = formatBalance(balance)
            config = config.copy(balance = balance)
        })

        effectRows = EFFECT_SPECS.map { spec -> buildEffectRow(spec) }

        bindAll()
    }

    /** Inflates one effect's switch and its param sliders, and wires both to [config]. */
    private fun buildEffectRow(spec: EffectSpec): EffectRow {
        val row = ItemEffectBinding.inflate(layoutInflater, binding.containerEffects, true)
        row.switchEffect.setText(spec.labelRes)
        row.switchEffect.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            config = config.withActive(spec, checked)
            row.containerParams.isVisible = checked
            push()
        }

        val paramRows = spec.params.map { param ->
            val paramRow = ItemEffectParamBinding.inflate(layoutInflater, row.containerParams, true)
            paramRow.textParamLabel.setText(param.labelRes)
            paramRow.seekParam.max = param.steps
            paramRow.seekParam.setOnSeekBarChangeListener(onSeek { progress ->
                val value = param.valueOf(progress)
                paramRow.textParamValue.text = param.format(value)
                config = config.withParam(spec, param, value)
            })
            paramRow
        }
        return EffectRow(spec, row, paramRows)
    }

    private fun bindAll() {
        updatingUi = true
        binding.switchEnabled.isChecked = config.enabled
        effectRows.forEach { row ->
            val active = config.isActive(row.spec)
            row.binding.switchEffect.isChecked = active
            row.binding.containerParams.isVisible = active
            row.spec.params.forEachIndexed { index, param ->
                val value = config.paramValue(row.spec, param)
                row.paramRows[index].seekParam.progress = param.progressOf(value)
                row.paramRows[index].textParamValue.text = param.format(value)
            }
        }

        binding.seekSpeed.progress = ((config.speed - MIN_SPEED) * SPEED_STEPS_PER_UNIT).roundToInt()
        binding.textSpeedValue.text = String.format(Locale.US, "%.2fx", config.speed)
        binding.seekBalance.progress = ((config.balance + 1f) * BALANCE_HALF_RANGE).roundToInt()
        binding.textBalanceValue.text = formatBalance(config.balance)

        binding.spinnerPreset.setSelection(presetIndex(), false)
        updatingUi = false
        bindBands()
    }

    private fun bindBands() {
        updatingUi = true
        bandRows.forEachIndexed { index, row ->
            val db = config.bandDb(index)
            row.seekBand.progress = ((db + EQ_MAX_DB) * BAND_STEPS_PER_DB).roundToInt()
            row.textBandValue.text = formatDb(db)
        }
        updatingUi = false
    }

    private fun syncPresetSpinner() {
        val index = presetIndex()
        if (binding.spinnerPreset.selectedItemPosition == index) return
        updatingUi = true
        binding.spinnerPreset.setSelection(index, false)
        updatingUi = false
    }

    private fun presetIndex(): Int =
        EqPresets.NAMES.indexOf(config.preset).takeIf { it >= 0 } ?: EqPresets.NAMES.lastIndex

    /** Only user-driven progress changes matter; programmatic ones are set while [updatingUi]. */
    private fun onSeek(onUserChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (updatingUi || !fromUser) return
            onUserChange(progress)
            push()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    /**
     * Same user-vs-programmatic guard as [onSeek], but pushes through [pushBand]
     * instead of the debounced whole-graph [push] - see the class doc comment.
     */
    private fun onEqBandSeek(index: Int, row: ItemEqBandBinding) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (updatingUi || !fromUser) return
            val db = bandDbOf(progress)
            row.textBandValue.text = formatDb(db)
            config = config.withBand(index, db)
            syncPresetSpinner()
            pushBand(index, db)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun push() {
        EffectsStore.update(config)
        binding.root.removeCallbacks(pushRunnable)
        binding.root.postDelayed(pushRunnable, PUSH_DEBOUNCE_MS)
    }

    /** No debounce, no graph rebuild - a direct `nativeSendFilterCommand` per tick. */
    private fun pushBand(index: Int, db: Float) {
        EffectsStore.update(config)
        PlaybackController.get(requireContext()).applyEqBand(index, db)
    }

    private fun bandDbOf(progress: Int): Float = progress / BAND_STEPS_PER_DB - EQ_MAX_DB

    private fun speedOf(progress: Int): Float = MIN_SPEED + progress / SPEED_STEPS_PER_UNIT

    private fun balanceOf(progress: Int): Float = (progress - BALANCE_HALF_RANGE) / BALANCE_HALF_RANGE

    private fun formatDb(db: Float): String = String.format(Locale.US, "%+.1f dB", db)

    private fun formatBalance(balance: Float): String = when {
        balance < -0.005f -> getString(R.string.effects_balance_left, (-balance * 100).roundToInt())
        balance > 0.005f -> getString(R.string.effects_balance_right, (balance * 100).roundToInt())
        else -> getString(R.string.effects_balance_center)
    }

    private fun formatFrequency(hz: Int): String =
        if (hz >= 1000) String.format(Locale.US, "%.1fk", hz / 1000f) else "$hz Hz"

    override fun onDestroyView() {
        binding.root.removeCallbacks(pushRunnable)
        // Anything still pending must not be lost just because the page scrolled away.
        PlaybackController.get(requireContext()).applyEffects(config)
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val BAND_STEPS_PER_DB = 2f      // SeekBar max 48 -> -12..+12 dB in 0.5 steps
        const val SPEED_STEPS_PER_UNIT = 100f // SeekBar max 175 -> 0.25x..2.00x
        const val BALANCE_HALF_RANGE = 50f    // SeekBar max 100 -> -1..+1
        const val PUSH_DEBOUNCE_MS = 120L
    }
}
