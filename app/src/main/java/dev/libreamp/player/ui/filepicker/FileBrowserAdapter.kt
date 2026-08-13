package dev.libreamp.player.ui.filepicker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.libreamp.player.R
import dev.libreamp.player.databinding.ItemFileBrowserEntryBinding

enum class FsEntryKind { PARENT, DIRECTORY, FILE }

data class FsEntry(
    val displayName: String,
    val target: java.io.File,
    val kind: FsEntryKind,
    /** Right-hand detail; empty when there is nothing cheap to say. */
    val meta: String = ""
)

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
        val context = b.root.context

        b.textName.text = entry.displayName
        b.textMeta.text = entry.meta

        val glyph = when (entry.kind) {
            FsEntryKind.PARENT -> R.string.file_glyph_parent
            FsEntryKind.DIRECTORY -> R.string.file_glyph_dir
            FsEntryKind.FILE -> R.string.file_glyph_audio
        }
        b.textIcon.setText(glyph)
        b.textIcon.setTextColor(
            ContextCompat.getColor(
                context,
                if (entry.kind == FsEntryKind.FILE) R.color.text_secondary else R.color.accent_edge
            )
        )
        b.textName.setTextColor(
            ContextCompat.getColor(
                context,
                if (entry.kind == FsEntryKind.FILE) R.color.text_soft else R.color.text_primary
            )
        )

        val checked = isChecked(entry)
        // ".." is navigation, not something that can be picked.
        b.checkboxEntry.visibility =
            if (entry.kind == FsEntryKind.PARENT) View.INVISIBLE else View.VISIBLE
        b.checkboxEntry.setOnCheckedChangeListener(null)
        b.checkboxEntry.isChecked = checked
        b.root.isSelected = checked && entry.kind != FsEntryKind.PARENT
        b.checkboxEntry.setOnCheckedChangeListener { _, isNowChecked ->
            onToggle(entry, isNowChecked)
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
