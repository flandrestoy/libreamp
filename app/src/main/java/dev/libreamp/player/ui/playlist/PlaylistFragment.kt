package dev.libreamp.player.ui.playlist

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
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
import dev.libreamp.player.data.db.PlaylistItem
import dev.libreamp.player.data.db.SortKey
import dev.libreamp.player.databinding.DialogTextInputBinding
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
        GroupKey.FORMAT -> R.string.group_format
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

    /**
     * Only selection is a mode now. Sorting and grouping used to be modes because a group
     * existed only while one was active; groups persist, so both are ordinary commands and
     * dragging no longer has to be fenced off behind a mode of its own.
     */
    private enum class Mode { NORMAL, SELECTION }
    private var mode: Mode = Mode.NORMAL

    private var searchOpen = false
    private var scrollRestored = false
    private var pendingCenterOnNowPlaying = false
    private var miniArtPath: String? = null

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
            onLongPress = { entry -> enterSelectionWith(entry) },
            onSelectionChanged = { updateModeTitle() },
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            onToggleCollapse = { group ->
                viewModel.setCollapsed(group.group.id, !group.group.collapsed)
            },
            onGroupMenu = { group, anchor -> showGroupMenu(group, anchor) }
        )
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPlaylist.adapter = adapter
        binding.fastScrollBar.attachTo(binding.recyclerPlaylist)

        touchHelper = ItemTouchHelper(
            PlaylistTouchCallback(adapter) { viewModel.applyArrangement(adapter.currentArrangement()) }
        )
        touchHelper.attachToRecyclerView(binding.recyclerPlaylist)

        binding.btnAdd.setOnClickListener {
            pickFilesLauncher.launch(Intent(requireContext(), FilePickerActivity::class.java))
        }
        binding.btnRemove.setOnClickListener { toggleSelectionMode() }
        binding.btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        binding.btnSort.setOnClickListener { showSortMenu(it) }
        binding.btnGroup.setOnClickListener { showGroupingMenu(it) }
        binding.btnEffects.setOnClickListener { host()?.openEffects() }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }
        binding.btnExitMode.setOnClickListener { toggleSelectionMode() }
        binding.btnSelectAll.setOnClickListener { adapter.toggleSelectAll() }
        binding.btnDeleteSelected.setOnClickListener {
            viewModel.deleteEntries(adapter.selectedEntries())
            toggleSelectionMode()
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
        updateModeBar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.visibleItems.collect { items ->
                    adapter.submitItems(items)
                    binding.fastScrollBar.invalidate()
                    updateEmptyState(items)
                    if (!scrollRestored && items.isNotEmpty()) {
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

    // ---- sorting: a command against one container ----

    private fun showSortMenu(anchor: View) {
        val menu = PopupMenu(requireContext(), anchor)
        val submenu = menu.menu.addSubMenu(
            Menu.NONE, SUBMENU_ID, 0, getString(R.string.menu_sort_whole_list)
        )
        SortKey.values().forEachIndexed { index, key ->
            submenu.add(Menu.NONE, index, index, key.label(requireContext()))
        }
        menu.menu.add(Menu.NONE, REVERSE_ID, 1, getString(R.string.sort_reverse))
        menu.setOnMenuItemClickListener { item ->
            // A submenu header opens its submenu; it is not a choice in its own right, and
            // its id would otherwise be read as a SortKey ordinal.
            if (item.hasSubMenu()) return@setOnMenuItemClickListener false
            if (item.itemId == REVERSE_ID) viewModel.reverseOrder()
            else viewModel.applySort(SortKey.values()[item.itemId])
            announceOrderChanged()
            true
        }
        menu.show()
    }

    // ---- grouping: commands that produce ordinary, editable groups ----

    /**
     * Scope is the selection when there is one and the whole list otherwise. Making the
     * user's selection the scope is what keeps "what happens to tracks that are already
     * grouped" their decision rather than a rule baked into the command.
     */
    private fun groupingScope(): List<PlaylistEntryEntity> =
        adapter.selectedEntries().ifEmpty { viewModel.allTracks() }

    private fun showGroupingMenu(anchor: View) {
        val selection = adapter.selectedEntries()
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(Menu.NONE, GROUP_SELECTED_ID, 0, getString(R.string.menu_group_selected))
            .isEnabled = selection.isNotEmpty()

        val submenu = menu.menu.addSubMenu(
            Menu.NONE, SUBMENU_ID, 1, getString(R.string.menu_auto_group)
        )
        GroupKey.values().forEachIndexed { index, key ->
            submenu.add(Menu.NONE, index, index, key.label(requireContext()))
        }

        menu.setOnMenuItemClickListener { item ->
            if (item.hasSubMenu()) return@setOnMenuItemClickListener false
            if (item.itemId == GROUP_SELECTED_ID) promptForNewGroup(selection)
            else viewModel.autoGroup(GroupKey.values()[item.itemId], groupingScope())
            true
        }
        menu.show()
    }

    private fun promptForNewGroup(tracks: List<PlaylistEntryEntity>) {
        if (tracks.isEmpty()) {
            toast(getString(R.string.msg_select_tracks_first))
            return
        }
        promptForText(
            title = getString(R.string.dialog_new_group_title),
            initial = getString(R.string.group_default_label)
        ) { label ->
            viewModel.createGroup(label, tracks)
            if (mode == Mode.SELECTION) toggleSelectionMode()
        }
    }

    /** A group's own verbs, anchored on its header. */
    private fun showGroupMenu(group: PlaylistItem.Group, anchor: View) {
        val groupId = group.group.id
        val menu = PopupMenu(requireContext(), anchor)
        with(menu.menu) {
            add(Menu.NONE, GROUP_RENAME_ID, 0, getString(R.string.menu_group_rename))
            val sortSubmenu = addSubMenu(
                Menu.NONE, SUBMENU_ID, 1, getString(R.string.menu_group_sort)
            )
            SortKey.values().forEachIndexed { index, key ->
                sortSubmenu.add(Menu.NONE, index, index, key.label(requireContext()))
            }
            add(Menu.NONE, GROUP_REVERSE_ID, 1, getString(R.string.menu_group_reverse))
            add(Menu.NONE, GROUP_SELECT_ID, 2, getString(R.string.menu_group_select))
            add(Menu.NONE, GROUP_UNGROUP_ID, 3, getString(R.string.menu_group_ungroup))
            add(Menu.NONE, GROUP_DELETE_ID, 4, getString(R.string.menu_group_delete))
        }
        menu.setOnMenuItemClickListener { item ->
            if (item.hasSubMenu()) return@setOnMenuItemClickListener false
            when (item.itemId) {
                GROUP_RENAME_ID -> promptForText(
                    title = getString(R.string.dialog_rename_group_title),
                    initial = group.group.label
                ) { viewModel.renameGroup(groupId, it) }

                GROUP_REVERSE_ID -> viewModel.reverseOrder(groupId)
                GROUP_SELECT_ID -> {
                    if (mode != Mode.SELECTION) toggleSelectionMode()
                    adapter.selectTracks(group.tracks)
                }
                GROUP_UNGROUP_ID -> viewModel.dissolveGroup(groupId)
                GROUP_DELETE_ID -> confirmDeleteGroup(group)
                else -> viewModel.applySort(SortKey.values()[item.itemId], groupId)
            }
            true
        }
        menu.show()
    }

    /**
     * Deleting the group and deleting its tracks are different things, and only the second
     * one loses anything — so only the second one asks.
     */
    private fun confirmDeleteGroup(group: PlaylistItem.Group) {
        val count = resources.getQuantityString(
            R.plurals.group_track_count, group.tracks.size, group.tracks.size
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_delete_group_title, group.group.label))
            .setMessage(getString(R.string.dialog_delete_group_message, count))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete_confirm) { _, _ ->
                viewModel.deleteGroupWithTracks(group.group.id)
            }
            .show()
    }

    private fun promptForText(title: String, initial: String, onAccept: (String) -> Unit) {
        val input = DialogTextInputBinding.inflate(layoutInflater)
        input.editText.setText(initial)
        input.editText.setSelection(initial.length)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val text = input.editText.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onAccept(text)
            }
            .show()
    }

    /** Sort and reverse rewrite the stored order, so say so rather than leaving it ambiguous. */
    private fun announceOrderChanged() = toast(getString(R.string.order_updated))

    private fun toast(message: String) {
        android.widget.Toast.makeText(
            requireContext(), message, android.widget.Toast.LENGTH_SHORT
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

    private fun updateEmptyState(items: List<PlaylistItem>) {
        val query = viewModel.searchQuery.value
        binding.textEmpty.isVisible = items.isEmpty()
        binding.textEmpty.text = when {
            items.isNotEmpty() -> ""
            query.isNotBlank() -> getString(R.string.playlist_no_results, query)
            else -> getString(R.string.playlist_empty)
        }
    }

    // ---- scrolling ----

    private fun consumeCenterRequest() {
        if (!pendingCenterOnNowPlaying) return
        val id = PlaybackController.get(requireContext()).state.value.entry?.id ?: return
        val position = adapter.rowPositionOf(id)
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

    // ---- playback / selection ----

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

    private fun enterSelectionWith(entry: PlaylistEntryEntity) {
        if (mode != Mode.SELECTION) toggleSelectionMode()
        adapter.selectTracks(listOf(entry))
    }

    private fun toggleSelectionMode() {
        mode = if (mode == Mode.SELECTION) Mode.NORMAL else Mode.SELECTION
        adapter.multiSelectMode = (mode == Mode.SELECTION)
        recomputeDragEnabled()
        updateModeBar()
    }

    /**
     * Reordering is always available now — a curated list is a thing you rearrange, and
     * there is no longer a grouping mode it could contradict. It stays off while the list
     * is filtered, because the visible neighbours a drop resolves against would not be the
     * real ones, and the arrangement read back off the rows would be a truncated playlist.
     */
    private fun recomputeDragEnabled() {
        adapter.dragEnabled = mode != Mode.SELECTION && viewModel.searchQuery.value.isBlank()
    }

    private fun updateModeBar() {
        binding.btnRemove.isActivated = mode == Mode.SELECTION
        binding.barMode.isVisible = mode == Mode.SELECTION
        updateModeTitle()
    }

    private fun updateModeTitle() {
        val count = adapter.selectedCount()
        binding.textModeTitle.text =
            if (count > 0) getString(R.string.mode_selected_count, count)
            else getString(R.string.mode_remove_title)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val PREFS_NAME = "playlist_ui"
        const val KEY_SCROLL_POSITION = "scroll_position"
        const val KEY_SCROLL_OFFSET = "scroll_offset"

        // Ids above every SortKey/GroupKey ordinal, which occupy the low range.
        const val REVERSE_ID = 100
        const val GROUP_SELECTED_ID = 101
        const val GROUP_RENAME_ID = 102
        const val GROUP_REVERSE_ID = 103
        const val GROUP_SELECT_ID = 104
        const val GROUP_UNGROUP_ID = 105
        const val GROUP_DELETE_ID = 106
        const val SUBMENU_ID = 200
    }
}
