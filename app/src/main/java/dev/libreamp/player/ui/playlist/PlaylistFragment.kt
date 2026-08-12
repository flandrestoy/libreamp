package dev.libreamp.player.ui.playlist

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
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
import kotlinx.coroutines.launch

class PlaylistFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaylistViewModel by viewModels {
        PlaylistViewModel.factory(requireActivity().application)
    }

    private lateinit var adapter: PlaylistAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var toastedManualSwitch = false

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(requireContext(), uris)
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
            onLongPress = { adapter.multiSelectMode = true; updateToolbarForSelection() },
            onSelectionToggled = { _, _ -> updateToolbarForSelection() },
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

        binding.toolbar.inflateMenu(R.menu.playlist_toolbar_menu)
        binding.toolbar.setOnMenuItemClickListener { item -> onToolbarItem(item) }

        binding.fabAddFiles.setOnClickListener {
            pickFilesLauncher.launch(arrayOf("audio/*", "video/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.visibleEntries.collect { list ->
                    adapter.submitList(list)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.group.collect { group ->
                    adapter.dragEnabled = group == GroupKey.NONE
                }
            }
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
        when (item.itemId) {
            R.id.action_sort -> showSortMenu()
            R.id.action_group -> showGroupMenu()
            R.id.action_delete_selected -> {
                viewModel.deleteEntries(adapter.selectedEntries())
                adapter.multiSelectMode = false
                updateToolbarForSelection()
            }
            else -> return false
        }
        return true
    }

    private fun showSortMenu() {
        val anchor = binding.toolbar.findViewById<View>(R.id.action_sort) ?: binding.toolbar
        val popup = PopupMenu(requireContext(), anchor)
        SortKey.values().forEachIndexed { i, key -> popup.menu.add(0, i, i, key.name) }
        popup.setOnMenuItemClickListener { item ->
            viewModel.setSort(SortKey.values()[item.itemId])
            true
        }
        popup.show()
    }

    private fun showGroupMenu() {
        val anchor = binding.toolbar.findViewById<View>(R.id.action_group) ?: binding.toolbar
        val popup = PopupMenu(requireContext(), anchor)
        GroupKey.values().forEachIndexed { i, key -> popup.menu.add(0, i, i, key.name) }
        popup.setOnMenuItemClickListener { item ->
            viewModel.setGroup(GroupKey.values()[item.itemId])
            true
        }
        popup.show()
    }

    private fun updateToolbarForSelection() {
        binding.toolbar.menu.clear()
        if (adapter.multiSelectMode) {
            binding.toolbar.inflateMenu(R.menu.playlist_selection_menu)
        } else {
            binding.toolbar.inflateMenu(R.menu.playlist_toolbar_menu)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
