package dev.libreamp.player.ui.playlist

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import dev.libreamp.player.MainActivity
import dev.libreamp.player.R
import dev.libreamp.player.data.db.GroupKey
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.data.db.SortKey
import dev.libreamp.player.databinding.FragmentPlaylistBinding
import dev.libreamp.player.playback.PlaybackController
import dev.libreamp.player.playback.PlaybackService
import dev.libreamp.player.playback.PlaybackState
import dev.libreamp.player.ui.filepicker.FilePickerActivity
import dev.libreamp.player.ui.widget.HatchDrawable
import kotlinx.coroutines.launch
import java.io.File

private fun SortKey.label(context: Context): String = context.getString(
    when (this) {
        SortKey.TITLE -> R.string.sort_title
        SortKey.ARTIST -> R.string.sort_artist
        SortKey.ALBUM -> R.string.sort_album
        SortKey.DURATION -> R.string.sort_duration
        SortKey.DATE_ADDED -> R.string.sort_date_added
        SortKey.LAST_MODIFIED -> R.string.sort_last_modified
    }
)

private fun GroupKey.label(context: Context): String = context.getString(
    when (this) {
        GroupKey.ARTIST -> R.string.group_artist
        GroupKey.ALBUM -> R.string.group_album
        GroupKey.MEDIA_TYPE -> R.string.group_media_type
    }
)

class PlaylistFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaylistViewModel by viewModels {
        PlaylistViewModel.factory(requireActivity().application)
    }

    private lateinit var adapter: PlaylistAdapter
    private lateinit var touchHelper: ItemTouchHelper

    /** Removal, sorting and grouping are mutually exclusive UI modes, toggled by the bottom bar. */
    private enum class Mode { NORMAL, SELECTION, SORTING, GROUPING }
    private var mode: Mode = Mode.NORMAL

    private var searchOpen = false
    private var scrollRestored = false
    private var pendingCenterOnNowPlaying = false
    private var miniArtPath: String? = null

    /**
     * The dropdown is a one-shot trigger, not state: it sits on a "choose"
     * placeholder and snaps back to it after firing, so this guards the reset
     * from re-firing.
     */
    private var suppressPickerCallback = false

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val layoutManager get() = binding.recyclerPlaylist.layoutManager as LinearLayoutManager

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(FilePickerActivity.EXTRA_SELECTED_PATHS)
            val uris = paths.orEmpty().map { android.net.Uri.fromFile(File(it)) }
            if (uris.isNotEmpty()) viewModel.addFiles(requireContext(), uris)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PlaylistAdapter(
            onClick = { entry -> playEntry(entry) },
            onLongPress = { if (mode != Mode.SELECTION) toggleMode(Mode.SELECTION) },
            onSelectionChanged = { updateModeTitle() },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPlaylist.adapter = adapter
        binding.fastScrollBar.attachTo(binding.recyclerPlaylist)

        touchHelper = ItemTouchHelper(
            PlaylistTouchCallback(adapter) { from, to -> onDragFinished(from, to) }
        )
        touchHelper.attachToRecyclerView(binding.recyclerPlaylist)

        binding.btnAdd.setOnClickListener {
            pickFilesLauncher.launch(Intent(requireContext(), FilePickerActivity::class.java))
        }
        binding.btnRemove.setOnClickListener { toggleMode(Mode.SELECTION) }
        binding.btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        binding.btnSort.setOnClickListener { toggleMode(Mode.SORTING) }
        binding.btnGroup.setOnClickListener { toggleMode(Mode.GROUPING) }
        binding.btnEffects.setOnClickListener { host()?.openEffects() }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }
        binding.btnExitMode.setOnClickListener { toggleMode(mode) }
        binding.btnSelectAll.setOnClickListener { adapter.toggleSelectAll() }
        binding.btnDeleteSelected.setOnClickListener {
            viewModel.deleteEntries(adapter.selectedEntries())
            toggleMode(Mode.SELECTION)
        }

        binding.editSearch.doOnTextChanged { text, _, _, _ ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
            recomputeDragEnabled()
        }

        setUpMiniPlayer()

        // The view can be recreated under a surviving fragment (pager offscreen limit),
        // so re-project the retained mode onto the fresh widgets and restore scroll again.
        searchOpen = false
        scrollRestored = false
        viewModel.setSearchQuery("")
        adapter.multiSelectMode = (mode == Mode.SELECTION)
        recomputeDragEnabled()
        updateBottomBarForMode()
        updateModeBar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.visibleEntries.collect { list ->
                    adapter.submitList(list)
                    binding.fastScrollBar.invalidate()
                    updateEmptyState(list)
                    if (!scrollRestored && list.isNotEmpty()) {
                        scrollRestored = true
                        restoreScrollPosition()
                    }
                    consumeCenterRequest()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackController.get(requireContext()).state.collect { state ->
                    adapter.nowPlayingId = state.entry?.id
                    bindMiniPlayer(state)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveScrollPosition()
    }

    // ---- mini player ----

    private fun setUpMiniPlayer() {
        binding.miniArt.setImageDrawable(HatchDrawable(requireContext(), small = true))
        binding.miniPlayer.setOnClickListener { host()?.showNowPlaying() }
        binding.miniPlayPause.setOnClickListener {
            val playing = PlaybackController.get(requireContext()).state.value.isPlaying
            sendServiceAction(if (playing) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY)
        }
        binding.miniNext.setOnClickListener { sendServiceAction(PlaybackService.ACTION_NEXT) }
    }

    private fun bindMiniPlayer(state: PlaybackState) {
        val entry = state.entry
        // With nothing loaded there is no track to summarise, and the bar would
        // just be an empty slab covering the last two rows of the list.
        val present = entry != null
        binding.miniPlayer.isVisible = present
        binding.miniProgress.isVisible = present
        if (entry == null) return

        binding.miniTitle.text = entry.title ?: entry.displayName
        binding.miniSubtitle.text = entry.artist.orEmpty()
        binding.miniPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.miniPlayPause.contentDescription =
            getString(if (state.isPlaying) R.string.action_pause else R.string.action_play)

        if (entry.artPath != miniArtPath) {
            miniArtPath = entry.artPath
            val bitmap = entry.artPath?.let { BitmapFactory.decodeFile(it) }
            if (bitmap != null) binding.miniArt.setImageBitmap(bitmap)
            else binding.miniArt.setImageDrawable(HatchDrawable(requireContext(), small = true))
        }

        val duration = state.durationUs
        binding.miniProgress.progress =
            if (duration > 0) ((state.positionUs * 1000) / duration).toInt() else 0
    }

    // ---- sorting / grouping: one-off rewrites of the stored order ----

    /**
     * One dropdown serves both modes; it is rebuilt when the mode changes rather
     * than kept as two rows, matching the single picker strip in the design.
     */
    private fun populatePicker() {
        val labels = when (mode) {
            Mode.SORTING -> listOf(getString(R.string.action_choose)) +
                SortKey.values().map { it.label(requireContext()) } +
                getString(R.string.sort_reverse)
            Mode.GROUPING -> listOf(getString(R.string.action_choose)) +
                GroupKey.values().map { it.label(requireContext()) }
            else -> return
        }
        binding.textPickerLabel.setText(
            if (mode == Mode.GROUPING) R.string.hint_group_by else R.string.hint_sort_by
        )

        suppressPickerCallback = true
        binding.spinnerPicker.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerPicker.setSelection(0, false)
        suppressPickerCallback = false

        binding.spinnerPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressPickerCallback || position == 0) return
                onPickerChosen(position)
                resetPicker()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun onPickerChosen(position: Int) {
        if (mode == Mode.GROUPING) {
            val key = GroupKey.values()[position - 1]
            viewModel.applyGroup(key)
            // Headers are only truthful while the order the grouping produced is
            // still intact, which is exactly the lifetime of this mode.
            adapter.groupKey = key
        } else {
            val keys = SortKey.values()
            if (position == keys.size + 1) viewModel.reverseOrder()
            else viewModel.applySort(keys[position - 1])
            adapter.groupKey = null
        }
        announceOrderChanged()
    }

    /** Posted, because Spinner ignores a selection change made from inside its own callback. */
    private fun resetPicker() {
        binding.spinnerPicker.post {
            suppressPickerCallback = true
            binding.spinnerPicker.setSelection(0, false)
            suppressPickerCallback = false
        }
    }

    /** Sort/group are destructive one-shots, so say so rather than leaving it ambiguous. */
    private fun announceOrderChanged() {
        android.widget.Toast.makeText(
            requireContext(), R.string.order_updated, android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // ---- search ----

    private fun openSearch() {
        searchOpen = true
        binding.rowSearch.isVisible = true
        binding.btnSearch.isActivated = true
        binding.editSearch.requestFocus()
        imm().showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
        recomputeDragEnabled()
    }

    private fun closeSearch() {
        searchOpen = false
        binding.editSearch.setText("")
        binding.rowSearch.isVisible = false
        binding.btnSearch.isActivated = false
        imm().hideSoftInputFromWindow(binding.root.windowToken, 0)
        recomputeDragEnabled()
        // Land on the track being played rather than on wherever the pre-search scroll was.
        pendingCenterOnNowPlaying = true
        binding.recyclerPlaylist.post { consumeCenterRequest() }
    }

    private fun imm() =
        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun updateEmptyState(list: List<PlaylistEntryEntity>) {
        val query = viewModel.searchQuery.value
        binding.textEmpty.isVisible = list.isEmpty()
        binding.textEmpty.text = when {
            list.isNotEmpty() -> ""
            query.isNotBlank() -> getString(R.string.playlist_no_results, query)
            else -> getString(R.string.playlist_empty)
        }
    }

    // ---- scrolling ----

    private fun consumeCenterRequest() {
        if (!pendingCenterOnNowPlaying) return
        val id = PlaybackController.get(requireContext()).state.value.entry?.id
        val position = adapter.currentList().indexOfFirst { it.id == id }
        if (position < 0) return
        pendingCenterOnNowPlaying = false
        binding.recyclerPlaylist.post {
            val recycler = _binding?.recyclerPlaylist ?: return@post
            val rowHeight = recycler.getChildAt(0)?.height ?: 0
            val offset = (recycler.height - rowHeight) / 2
            layoutManager.scrollToPositionWithOffset(position, offset.coerceAtLeast(0))
        }
    }

    private fun saveScrollPosition() {
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val offset = layoutManager.findViewByPosition(position)?.top ?: 0
        prefs.edit()
            .putInt(KEY_SCROLL_POSITION, position)
            .putInt(KEY_SCROLL_OFFSET, offset)
            .apply()
    }

    private fun restoreScrollPosition() {
        val position = prefs.getInt(KEY_SCROLL_POSITION, 0)
        if (position <= 0 || position >= adapter.itemCount) return
        layoutManager.scrollToPositionWithOffset(position, prefs.getInt(KEY_SCROLL_OFFSET, 0))
    }

    // ---- playback / modes ----

    private fun host(): MainActivity? = activity as? MainActivity

    private fun playEntry(entry: PlaylistEntryEntity) {
        val ctx = requireContext()
        val intent = Intent(ctx, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY_ENTRY)
            .putExtra(PlaybackService.EXTRA_ENTRY_ID, entry.id)
        ContextCompat.startForegroundService(ctx, intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(requireContext(), PlaybackService::class.java).setAction(action)
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun onDragFinished(from: Int, to: Int) {
        val list = adapter.currentList()
        val moved = list.getOrNull(to) ?: return
        val before = list.getOrNull(to - 1)?.takeIf { it.id != moved.id }
        val after = list.getOrNull(to + 1)?.takeIf { it.id != moved.id }
        viewModel.applyManualMove(moved, before, after)
    }

    /** Tapping the active mode's button again returns to NORMAL; the three action modes are exclusive. */
    private fun toggleMode(target: Mode) {
        mode = if (mode == target) Mode.NORMAL else target
        adapter.multiSelectMode = (mode == Mode.SELECTION)
        if (mode != Mode.GROUPING) adapter.groupKey = null
        recomputeDragEnabled()
        updateBottomBarForMode()
        updateModeBar()
        if (mode == Mode.SORTING || mode == Mode.GROUPING) populatePicker()
    }

    /**
     * Manual reordering belongs to sorting mode, and is meaningless while the list is
     * filtered — the visible neighbours the drop is resolved against would not be the
     * real ones.
     */
    private fun recomputeDragEnabled() {
        adapter.dragEnabled = mode == Mode.SORTING && viewModel.searchQuery.value.isBlank()
    }

    private fun updateBottomBarForMode() {
        binding.btnRemove.isActivated = mode == Mode.SELECTION
        binding.btnSort.isActivated = mode == Mode.SORTING
        binding.btnGroup.isActivated = mode == Mode.GROUPING
        binding.rowPicker.isVisible = mode == Mode.SORTING || mode == Mode.GROUPING
    }

    private fun updateModeBar() {
        binding.barMode.isVisible = mode != Mode.NORMAL
        // Select-all and delete only mean anything against a selection.
        val selecting = mode == Mode.SELECTION
        binding.btnSelectAll.isVisible = selecting
        binding.btnDeleteSelected.isVisible = selecting
        updateModeTitle()
    }

    private fun updateModeTitle() {
        val count = adapter.selectedCount()
        binding.textModeTitle.text = when (mode) {
            Mode.SELECTION ->
                if (count > 0) getString(R.string.mode_selected_count, count)
                else getString(R.string.mode_remove_title)
            Mode.SORTING -> getString(R.string.mode_sort_title)
            Mode.GROUPING -> getString(R.string.mode_group_title)
            Mode.NORMAL -> ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val PREFS_NAME = "playlist_ui"
        const val KEY_SCROLL_POSITION = "scroll_position"
        const val KEY_SCROLL_OFFSET = "scroll_offset"
    }
}
