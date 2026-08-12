package dev.libreamp.player.ui.filepicker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.libreamp.player.databinding.ItemFileBrowserEntryBinding

enum class FsEntryKind { PARENT, DIRECTORY, FILE }

data class FsEntry(val displayName: String, val target: java.io.File, val kind: FsEntryKind)

class FileBrowserAdapter(
    private val onNavigate: (FsEntry) -> Unit,
    private val onToggle: (FsEntry, Boolean) -> Unit,
    private val isChecked: (FsEntry) -> Boolean
) : RecyclerView.Adapter<FileBrowserAdapter.ViewHolder>() {

    private var items: List<FsEntry> = emptyList()

    fun submitList(newItems: List<FsEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun refreshCheckboxes() {
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemFileBrowserEntryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBrowserEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val b = holder.binding
        b.textName.text = entry.displayName

        if (entry.kind == FsEntryKind.PARENT) {
            b.checkboxEntry.visibility = android.view.View.INVISIBLE
        } else {
            b.checkboxEntry.visibility = android.view.View.VISIBLE
            b.checkboxEntry.setOnCheckedChangeListener(null)
            b.checkboxEntry.isChecked = isChecked(entry)
            b.checkboxEntry.setOnCheckedChangeListener { _, checked -> onToggle(entry, checked) }
        }

        b.root.setOnClickListener {
            when (entry.kind) {
                FsEntryKind.PARENT, FsEntryKind.DIRECTORY -> onNavigate(entry)
                FsEntryKind.FILE -> b.checkboxEntry.isChecked = !b.checkboxEntry.isChecked
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
