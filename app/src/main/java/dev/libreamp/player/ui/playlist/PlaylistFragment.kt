package dev.libreamp.player.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
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

    /** Only removal is a mode now — sorting and grouping are one-shot dialogs. */
    private enum class Mode { NORMAL, SELECTION }
    private var mode: Mode = Mode.NORMAL

    private var searchOpen = false
    private var scrollRestored = false
    private var pendingCenterOnNowPlaying = false

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
            onLongPress = { if (mode != Mode.SELECTION) toggleSelectionMode() },
            onSelectionToggled = { _, _ -> },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPlaylist.adapter = adapter

        touchHelper = ItemTouchHelper(
            PlaylistTouchCallback(adapter) { from, to -> onDragFinished(from, to) }
        )
        touchHelper.attachToRecyclerView(binding.recyclerPlaylist)

        binding.toolbar.setOnMenuItemClickListener { item -> onToolbarItem(item) }

        binding.btnAdd.setOnClickListener {
            pickFilesLauncher.launch(Intent(requireContext(), FilePickerActivity::class.java))
        }
        binding.btnRemove.setOnClickListener { toggleSelectionMode() }
        binding.btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        binding.btnSort.setOnClickListener { showSortDialog() }
        binding.btnGroup.setOnClickListener { showGroupDialog() }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }

        binding.editSearch.doOnTextChanged { text, _, _, _ ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
            recomputeDragEnabled()
        }

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

    // ---- sorting / grouping (one-off rewrites of the stored order) ----

    private fun showSortDialog() {
        val keys = SortKey.values()
        val labels = keys.map { it.label(requireContext()) } + getString(R.string.sort_reverse)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_sort)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == keys.size) viewModel.reverseOrder() else viewModel.applySort(keys[which])
                announceOrderChanged()
            }
            .show()
    }

    private fun showGroupDialog() {
        val keys = GroupKey.values()
        val labels = keys.map { it.label(requireContext()) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_group)
            .setItems(labels.toTypedArray()) { _, which ->
                viewModel.applyGroup(keys[which])
                announceOrderChanged()
            }
            .show()
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
        if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
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

    // ---- playback / selection ----

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
            toggleSelectionMode()
            return true
        }
        return false
    }

    private fun toggleSelectionMode() {
        mode = if (mode == Mode.SELECTION) Mode.NORMAL else Mode.SELECTION
        adapter.multiSelectMode = (mode == Mode.SELECTION)
        recomputeDragEnabled()
        updateBottomBarForMode()
        updateToolbarForSelection()
    }

    /**
     * Manual reordering is meaningless while the list is filtered — the visible
     * neighbours the drop is resolved against would not be the real ones.
     */
    private fun recomputeDragEnabled() {
        adapter.dragEnabled = mode != Mode.SELECTION && viewModel.searchQuery.value.isBlank()
    }

    private fun updateBottomBarForMode() {
        binding.btnRemove.isActivated = mode == Mode.SELECTION
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
