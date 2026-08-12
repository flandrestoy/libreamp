package dev.libreamp.player.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
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
import dev.libreamp.player.playback.PlaybackService
import dev.libreamp.player.ui.filepicker.FilePickerActivity
import kotlinx.coroutines.launch
import java.io.File

private fun SortKey.label(context: Context): String = context.getString(
    when (this) {
        SortKey.MANUAL -> R.string.sort_manual
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
        GroupKey.NONE -> R.string.group_none
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
    private var toastedManualSwitch = false

    /** Removal, sorting and grouping are mutually exclusive UI modes, toggled by the bottom bar. */
    private enum class Mode { NORMAL, SELECTION, SORTING, GROUPING }
    private var mode: Mode = Mode.NORMAL

    private var suppressSortCallback = false
    private var suppressGroupCallback = false

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

        touchHelper = ItemTouchHelper(
            PlaylistTouchCallback(adapter) { from, to ->
                onDragFinished(from, to)
            }
        )
        touchHelper.attachToRecyclerView(binding.recyclerPlaylist)

        binding.toolbar.setOnMenuItemClickListener { item -> onToolbarItem(item) }

        binding.btnAdd.setOnClickListener {
            pickFilesLauncher.launch(Intent(requireContext(), FilePickerActivity::class.java))
        }
        binding.btnRemove.setOnClickListener { toggleMode(Mode.SELECTION) }
        binding.btnSort.setOnClickListener { toggleMode(Mode.SORTING) }
        binding.btnGroup.setOnClickListener { toggleMode(Mode.GROUPING) }

        setUpSortSpinner()
        setUpGroupSpinner()
        updateBottomBarForMode()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.visibleEntries.collect { list ->
                    adapter.submitList(list)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sort.collect { key ->
                    suppressSortCallback = true
                    binding.spinnerSortBy.setSelection(key.ordinal, false)
                    suppressSortCallback = false
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.group.collect { key ->
                    suppressGroupCallback = true
                    binding.spinnerGroupBy.setSelection(key.ordinal, false)
                    suppressGroupCallback = false
                    recomputeDragEnabled()
                }
            }
        }
    }

    private fun setUpSortSpinner() {
        val labels = SortKey.values().map { it.label(requireContext()) }
        binding.spinnerSortBy.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerSortBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSortCallback) return
                viewModel.setSort(SortKey.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpGroupSpinner() {
        val labels = GroupKey.values().map { it.label(requireContext()) }
        binding.spinnerGroupBy.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerGroupBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressGroupCallback) return
                viewModel.setGroup(GroupKey.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun playEntry(entry: PlaylistEntryEntity) {
        val ctx = requireContext()
        val intent = Intent(ctx, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY_ENTRY)
            .putExtra(PlaybackService.EXTRA_ENTRY_ID, entry.id)
        ContextCompat.startForegroundService(ctx, intent)
    }

    private fun onDragFinished(from: Int, to: Int) {
        if (viewModel.sort.value != SortKey.MANUAL) {
            viewModel.setSort(SortKey.MANUAL)
            if (!toastedManualSwitch) {
                toastedManualSwitch = true
                android.widget.Toast.makeText(
                    requireContext(), R.string.switched_to_manual_sort, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
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

    /** Manual reordering only makes sense while sorting mode is active and no grouping is applied. */
    private fun recomputeDragEnabled() {
        adapter.dragEnabled = mode == Mode.SORTING && viewModel.group.value == GroupKey.NONE
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
}
