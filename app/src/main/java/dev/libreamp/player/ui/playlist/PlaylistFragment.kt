package dev.libreamp.player.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
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
import dev.libreamp.player.R
import dev.libreamp.player.data.db.GroupKey
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.data.db.SortKey
import dev.libreamp.player.databinding.FragmentPlaylistBinding
import dev.libreamp.player.playback.PlaybackController
import dev.libreamp.player.playback.PlaybackService
import dev.libreamp.player.ui.filepicker.FilePickerActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /**
     * The dropdowns are one-shot triggers, not state: they sit on a "choose" placeholder
     * and snap back to it after firing, so these guard the reset from re-firing.
     */
    private var suppressSortCallback = false
    private var suppressGroupCallback = false

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
            onSelectionToggled = { _, _ -> },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPlaylist.adapter = adapter
        binding.fastScrollBar.attachTo(binding.recyclerPlaylist)

        touchHelper = ItemTouchHelper(
            PlaylistTouchCallback(adapter) { from, to -> onDragFinished(from, to) }
        )
        touchHelper.attachToRecyclerView(binding.recyclerPlaylist)

        binding.toolbar.setOnMenuItemClickListener { item -> onToolbarItem(item) }

        binding.btnAdd.setOnClickListener {
            pickFilesLauncher.launch(Intent(requireContext(), FilePickerActivity::class.java))
        }
        binding.btnRemove.setOnClickListener { toggleMode(Mode.SELECTION) }
        binding.btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        binding.btnSort.setOnClickListener { toggleMode(Mode.SORTING) }
        binding.btnGroup.setOnClickListener { toggleMode(Mode.GROUPING) }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }

        binding.editSearch.doOnTextChanged { text, _, _, _ ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
            recomputeDragEnabled()
        }

        setUpSortSpinner()
        setUpGroupSpinner()

        // The view can be recreated under a surviving fragment (pager offscreen limit),
        // so re-project the retained mode onto the fresh widgets and restore scroll again.
        searchOpen = false
        scrollRestored = false
        viewModel.setSearchQuery("")
        adapter.multiSelectMode = (mode == Mode.SELECTION)
        recomputeDragEnabled()
        updateBottomBarForMode()
        updateToolbarForSelection()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.visibleEntries.collect { list ->
                    adapter.submitList(list)
                    binding.fastScrollBar.invalidate()
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
                PlaybackController.get(requireContext()).state
                    .map { it.entry?.id }
                    .distinctUntilChanged()
                    .collect { adapter.nowPlayingId = it }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveScrollPosition()
    }

    // ---- sorting / grouping: one-off rewrites of the stored order ----

    /** Placeholder first, then the keys, then the reverse action. */
    private fun setUpSortSpinner() {
        val labels = listOf(getString(R.string.action_choose)) +
            SortKey.values().map { it.label(requireContext()) } +
            getString(R.string.sort_reverse)
        binding.spinnerSortBy.adapter = spinnerAdapter(labels)
        binding.spinnerSortBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSortCallback || position == 0) return
                val keys = SortKey.values()
                if (position == keys.size + 1) viewModel.reverseOrder()
                else viewModel.applySort(keys[position - 1])
                announceOrderChanged()
                resetSpinner(binding.spinnerSortBy) { suppressSortCallback = it }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpGroupSpinner() {
        val labels = listOf(getString(R.string.action_choose)) +
            GroupKey.values().map { it.label(requireContext()) }
        binding.spinnerGroupBy.adapter = spinnerAdapter(labels)
        binding.spinnerGroupBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressGroupCallback || position == 0) return
                viewModel.applyGroup(GroupKey.values()[position - 1])
                announceOrderChanged()
                resetSpinner(binding.spinnerGroupBy) { suppressGroupCallback = it }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun spinnerAdapter(labels: List<String>) = ArrayAdapter(
        requireContext(), android.R.layout.simple_spinner_item, labels
    ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    /** Posted, because Spinner ignores a selection change made from inside its own callback. */
    private fun resetSpinner(spinner: android.widget.Spinner, setSuppressed: (Boolean) -> Unit) {
        spinner.post {
            setSuppressed(true)
            spinner.setSelection(0, false)
            setSuppressed(false)
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
        binding.rowSearch.visibility = View.VISIBLE
        binding.btnSearch.isActivated = true
        binding.editSearch.requestFocus()
        imm().showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
        recomputeDragEnabled()
    }

    private fun closeSearch() {
        searchOpen = false
        binding.editSearch.setText("")
        binding.rowSearch.visibility = View.GONE
        binding.btnSearch.isActivated = false
        imm().hideSoftInputFromWindow(binding.root.windowToken, 0)
        recomputeDragEnabled()
        // Land on the track being played rather than on wherever the pre-search scroll was.
        pendingCenterOnNowPlaying = true
        binding.recyclerPlaylist.post { consumeCenterRequest() }
    }

    private fun imm() =
        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

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

    private fun playEntry(entry: PlaylistEntryEntity) {
        val ctx = requireContext()
        val intent = Intent(ctx, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY_ENTRY)
            .putExtra(PlaybackService.EXTRA_ENTRY_ID, entry.id)
        ContextCompat.startForegroundService(ctx, intent)
    }

    private fun onDragFinished(from: Int, to: Int) {
        val list = adapter.currentList()
        val moved = list[to]
        val before = list.getOrNull(to - 1)?.takeIf { it.id != moved.id }
        val after = list.getOrNull(to + 1)?.takeIf { it.id != moved.id }
        viewModel.applyManualMove(moved, before, after)
    }

    private fun onToolbarItem(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_delete_selected) {
            viewModel.deleteEntries(adapter.selectedEntries())
            toggleMode(Mode.SELECTION)
            return true
        }
        return false
    }

    /** Tapping the active mode's button again returns to NORMAL; the three action modes are exclusive. */
    private fun toggleMode(target: Mode) {
        mode = if (mode == target) Mode.NORMAL else target
        adapter.multiSelectMode = (mode == Mode.SELECTION)
        recomputeDragEnabled()
        updateBottomBarForMode()
        updateToolbarForSelection()
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
        binding.rowSortBy.visibility = if (mode == Mode.SORTING) View.VISIBLE else View.GONE
        binding.rowGroupBy.visibility = if (mode == Mode.GROUPING) View.VISIBLE else View.GONE
    }

    private fun updateToolbarForSelection() {
        binding.toolbar.menu.clear()
        if (mode == Mode.SELECTION) {
            binding.toolbar.inflateMenu(R.menu.playlist_selection_menu)
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
