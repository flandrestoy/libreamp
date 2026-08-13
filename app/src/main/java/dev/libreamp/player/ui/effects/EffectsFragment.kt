package dev.libreamp.player.ui.effects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dev.libreamp.player.MainActivity
import dev.libreamp.player.R
import dev.libreamp.player.data.effects.EFFECT_SPECS
import dev.libreamp.player.data.effects.EQ_BAND_COUNT
import dev.libreamp.player.data.effects.EQ_FREQUENCIES
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
 *
 * Presented as an overlay above the pager rather than as a page of it, so the back
 * arrow in its header is the only way out and [MainActivity] owns the dismissal.
 */
class EffectsFragment : Fragment() {

    private var _binding: FragmentEffectsBinding? = null
    private val binding get() = _binding!!

    private lateinit var bandRows: List<ItemEqBandBinding>
    private lateinit var effectRows: List<EffectRow>
    private var config = EffectsConfig()

    /** Only one effect's params are open at a time, matching the design's accordion. */
    private var expandedEffectId: String? = null

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

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.closeOverlay() }

        binding.textEqSection.text = getString(R.string.effects_section_eq, EQ_BAND_COUNT)

        bandRows = (0 until EQ_BAND_COUNT).map { index ->
            val row = ItemEqBandBinding.inflate(layoutInflater, binding.containerBands, true)
            row.textBandFreq.text = formatFrequency(EQ_FREQUENCIES[index])
            row.faderBand.onGainChanged = { db -> onBandDragged(index, row, db) }
            row
        }

        binding.groupMaster.setOnClickListener {
            config = config.copy(enabled = !config.enabled)
            bindMaster()
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
            binding.textSpeedValue.text = String.format(Locale.US, "%.2f×", speed)
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

    /** Inflates one effect's row and its param sliders, and wires both to [config]. */
    private fun buildEffectRow(spec: EffectSpec): EffectRow {
        val row = ItemEffectBinding.inflate(layoutInflater, binding.containerEffects, true)
        row.textEffectLabel.setText(spec.labelRes)
        row.textEffectId.text = spec.id

        // The switch is inside the row's tap target, so it has to claim its own
        // touches or toggling an effect would also collapse or expand it.
        row.switchEffect.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            config = config.withActive(spec, checked)
            row.textEffectLabel.setTextColor(labelColorFor(checked))
            push()
        }
        row.rowEffect.setOnClickListener { toggleExpanded(spec.id) }

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

    private fun toggleExpanded(id: String) {
        expandedEffectId = if (expandedEffectId == id) null else id
        bindExpansion()
    }

    private fun bindExpansion() {
        effectRows.forEach { row ->
            val open = row.spec.id == expandedEffectId
            row.binding.containerParams.isVisible = open
            row.binding.rowEffect.isActivated = open
            row.binding.textChevron.text = if (open) CHEVRON_OPEN else CHEVRON_CLOSED
        }
    }

    private fun bindAll() {
        updatingUi = true
        bindMaster()
        effectRows.forEach { row ->
            val active = config.isActive(row.spec)
            row.binding.switchEffect.isChecked = active
            row.binding.textEffectLabel.setTextColor(labelColorFor(active))
            row.spec.params.forEachIndexed { index, param ->
                val value = config.paramValue(row.spec, param)
                row.paramRows[index].seekParam.progress = param.progressOf(value)
                row.paramRows[index].textParamValue.text = param.format(value)
            }
        }

        binding.seekSpeed.progress = ((config.speed - MIN_SPEED) * SPEED_STEPS_PER_UNIT).roundToInt()
        binding.textSpeedValue.text = String.format(Locale.US, "%.2f×", config.speed)
        binding.seekBalance.progress = ((config.balance + 1f) * BALANCE_HALF_RANGE).roundToInt()
        binding.textBalanceValue.text = formatBalance(config.balance)

        binding.spinnerPreset.setSelection(presetIndex(), false)
        updatingUi = false
        bindBands()
        bindExpansion()
    }

    /**
     * The whole body dims when the chain is bypassed: the controls stay readable
     * and adjustable, but nothing below the header is doing anything.
     */
    private fun bindMaster() {
        // The switch is non-clickable so the whole label+switch group is one
        // target; it still carries its own checked state for its drawables.
        binding.switchEnabled.isChecked = config.enabled
        binding.textMasterState.setText(
            if (config.enabled) R.string.effects_master_on else R.string.effects_master_bypass
        )
        binding.textMasterState.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (config.enabled) R.color.accent else R.color.text_secondary
            )
        )
        binding.scrollEffects.alpha = if (config.enabled) 1f else BYPASSED_ALPHA
    }

    private fun bindBands() {
        updatingUi = true
        bandRows.forEachIndexed { index, row ->
            val db = config.bandDb(index)
            row.faderBand.gainDb = db
            bindBandValue(row, db)
        }
        updatingUi = false
    }

    private fun bindBandValue(row: ItemEqBandBinding, db: Float) {
        row.textBandValue.text = formatDb(db)
        row.textBandValue.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (db == 0f) R.color.text_faint else R.color.accent_edge
            )
        )
    }

    /**
     * Pushes through [pushBand] rather than the debounced whole-graph [push] -
     * see the class doc comment.
     */
    private fun onBandDragged(index: Int, row: ItemEqBandBinding, db: Float) {
        if (updatingUi) return
        bindBandValue(row, db)
        config = config.withBand(index, db)
        syncPresetSpinner()
        pushBand(index, db)
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

    private fun labelColorFor(active: Boolean): Int = ContextCompat.getColor(
        requireContext(), if (active) R.color.text_primary else R.color.text_secondary
    )

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

    private fun speedOf(progress: Int): Float = MIN_SPEED + progress / SPEED_STEPS_PER_UNIT

    private fun balanceOf(progress: Int): Float = (progress - BALANCE_HALF_RANGE) / BALANCE_HALF_RANGE

    /** Half-decibel steps, so the fraction only shows when there is one. */
    private fun formatDb(db: Float): String = when {
        db == 0f -> "0"
        db % 1f == 0f -> String.format(Locale.US, "%+.0f", db)
        else -> String.format(Locale.US, "%+.1f", db)
    }

    private fun formatBalance(balance: Float): String = when {
        balance < -0.005f -> getString(R.string.effects_balance_left, (-balance * 100).roundToInt())
        balance > 0.005f -> getString(R.string.effects_balance_right, (balance * 100).roundToInt())
        else -> getString(R.string.effects_balance_center)
    }

    /** Compact ticks under a 26dp column: "65", "1k", "1.5k", "20k". */
    private fun formatFrequency(hz: Int): String {
        if (hz < 1000) return hz.toString()
        val k = hz / 1000f
        // One decimal below 10k, where 1.5k and 2.1k are worth telling apart.
        return if (k < 10f && k % 1f >= 0.05f) String.format(Locale.US, "%.1fk", k)
        else "${k.roundToInt()}k"
    }

    override fun onDestroyView() {
        binding.root.removeCallbacks(pushRunnable)
        // Anything still pending must not be lost just because the screen closed.
        PlaybackController.get(requireContext()).applyEffects(config)
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val SPEED_STEPS_PER_UNIT = 100f // SeekBar max 175 -> 0.25x..2.00x
        const val BALANCE_HALF_RANGE = 50f    // SeekBar max 100 -> -1..+1
        const val PUSH_DEBOUNCE_MS = 120L
        const val BYPASSED_ALPHA = 0.45f
        const val CHEVRON_OPEN = "▾"
        const val CHEVRON_CLOSED = "▸"
    }
}
