package dev.libreamp.player.ui.playlist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.databinding.ItemPlaylistEntryBinding
import java.util.concurrent.TimeUnit

class PlaylistAdapter(
    private val onClick: (PlaylistEntryEntity) -> Unit,
    private val onLongPress: (PlaylistEntryEntity) -> Unit,
    private val onSelectionToggled: (PlaylistEntryEntity, Boolean) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private var items: List<PlaylistEntryEntity> = emptyList()
    private val selectedIds = mutableSetOf<Long>()
    private val artCache = object : LruCache<String, Bitmap>(ART_CACHE_SLOTS) {}

    /** Drives drag-handle visibility, so a change has to re-bind the rows. */
    var dragEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var multiSelectMode: Boolean = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    /** Row to highlight; set from the engine's state, so guard against redundant redraws. */
    var nowPlayingId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<PlaylistEntryEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun currentList(): List<PlaylistEntryEntity> = items

    fun itemAt(position: Int): PlaylistEntryEntity = items[position]

    fun selectedEntries(): List<PlaylistEntryEntity> = items.filter { it.id in selectedIds }

    fun selectAll() {
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
    }

    /** Purely visual reorder while dragging; persistence happens once on drag-end. */
    fun moveItemVisually(from: Int, to: Int) {
        items = items.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }

    inner class ViewHolder(val binding: ItemPlaylistEntryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val b = holder.binding
        b.textTrackNumber.text = (position + 1).toString()
        b.root.isActivated = entry.id == nowPlayingId
        b.textTitle.text = entry.title ?: entry.displayName
        val subtitleParts = listOfNotNull(entry.artist, entry.album).filter { it.isNotBlank() }
        b.textSubtitle.text = subtitleParts.joinToString(" • ")
        b.textDuration.text = formatDuration(entry.durationMs)

        val art = entry.artPath?.let { loadArt(it) }
        if (art != null) {
            b.imageArt.setImageBitmap(art)
        } else {
            b.imageArt.setImageResource(dev.libreamp.player.R.drawable.ic_placeholder_album_art)
        }

        b.checkboxSelect.visibility = if (multiSelectMode) android.view.View.VISIBLE else android.view.View.GONE
        b.checkboxSelect.setOnCheckedChangeListener(null)
        b.checkboxSelect.isChecked = entry.id in selectedIds
        b.checkboxSelect.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(entry.id) else selectedIds.remove(entry.id)
            onSelectionToggled(entry, checked)
        }

        b.dragHandle.visibility = if (dragEnabled && !multiSelectMode) android.view.View.VISIBLE else android.view.View.GONE
        b.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        b.root.setOnClickListener {
            if (multiSelectMode) {
                b.checkboxSelect.isChecked = !b.checkboxSelect.isChecked
            } else {
                onClick(entry)
            }
        }
        b.root.setOnLongClickListener {
            if (!multiSelectMode) onLongPress(entry)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    private fun loadArt(path: String): Bitmap? {
        artCache.get(path)?.let { return it }
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        artCache.put(path, bmp)
        return bmp
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format("%d:%02d", m, s)
    }

    companion object {
        private const val ART_CACHE_SLOTS = 40
    }
}
