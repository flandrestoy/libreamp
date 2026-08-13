package dev.libreamp.player.ui.playlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.libreamp.player.data.db.GroupKey
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.databinding.ItemPlaylistEntryBinding
import dev.libreamp.player.databinding.ItemPlaylistHeaderBinding
import dev.libreamp.player.ui.widget.HatchDrawable
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlaylistAdapter(
    private val onClick: (PlaylistEntryEntity) -> Unit,
    private val onLongPress: (PlaylistEntryEntity) -> Unit,
    private val onSelectionChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** What the list actually renders: tracks, plus group dividers when grouped. */
    private sealed class Row {
        class Header(val label: String) : Row()
        class Track(val entry: PlaylistEntryEntity, val number: Int) : Row()
    }

    private var entries: List<PlaylistEntryEntity> = emptyList()
    private var rows: List<Row> = emptyList()
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
            if (field == value) return
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    /**
     * Group dividers are only shown while grouping is the active mode. Grouping
     * in this app is a one-shot rewrite of the stored order rather than a
     * retained view state, so once the user leaves the mode there is nothing
     * that still guarantees runs of a key stay contiguous — and headers over a
     * list that has since been re-sorted would be actively wrong.
     */
    var groupKey: GroupKey? = null
        set(value) {
            if (field == value) return
            field = value
            rebuildRows()
        }

    /** Row to highlight; set from the engine's state, so guard against redundant redraws. */
    var nowPlayingId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<PlaylistEntryEntity>) {
        entries = newItems
        rebuildRows()
    }

    fun currentList(): List<PlaylistEntryEntity> = entries

    fun selectedEntries(): List<PlaylistEntryEntity> = entries.filter { it.id in selectedIds }

    fun selectedCount(): Int = selectedIds.size

    /** Select-all doubles as clear-all once everything is already picked. */
    fun toggleSelectAll() {
        if (selectedIds.size == entries.size) selectedIds.clear()
        else selectedIds.addAll(entries.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /**
     * Purely visual reorder while dragging; persistence happens once on
     * drag-end. Dragging is only offered in sorting mode, which never has
     * grouping active, so rows and entries are 1:1 here and the row index is
     * the entry index — [PlaylistTouchCallback] refuses the drag otherwise.
     */
    fun moveItemVisually(from: Int, to: Int) {
        if (groupKey != null) return
        entries = entries.toMutableList().apply { add(to, removeAt(from)) }
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }

    fun entryAt(rowPosition: Int): PlaylistEntryEntity? =
        (rows.getOrNull(rowPosition) as? Row.Track)?.entry

    fun isTrackRow(rowPosition: Int): Boolean = rows.getOrNull(rowPosition) is Row.Track

    private fun rebuildRows() {
        val key = groupKey
        val built = mutableListOf<Row>()
        var lastLabel: String? = null
        entries.forEachIndexed { index, entry ->
            if (key != null) {
                val label = labelFor(entry, key)
                if (label != lastLabel) {
                    lastLabel = label
                    built.add(Row.Header(label))
                }
            }
            built.add(Row.Track(entry, index + 1))
        }
        rows = built
        notifyDataSetChanged()
    }

    private fun labelFor(entry: PlaylistEntryEntity, key: GroupKey): String = when (key) {
        GroupKey.ARTIST -> entry.artist
        GroupKey.ALBUM -> entry.album
        GroupKey.MEDIA_TYPE -> entry.displayName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }?.uppercase(Locale.US)
    }?.takeIf { it.isNotBlank() } ?: UNKNOWN_GROUP

    // ---- view holders ----

    class HeaderHolder(val binding: ItemPlaylistHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class TrackHolder(val binding: ItemPlaylistEntryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemPlaylistHeaderBinding.inflate(inflater, parent, false))
        } else {
            TrackHolder(ItemPlaylistEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).binding.textGroupHeader.text = row.label
            is Row.Track -> bindTrack(holder as TrackHolder, row)
        }
    }

    private fun bindTrack(holder: TrackHolder, row: Row.Track) {
        val entry = row.entry
        val b = holder.binding
        val selected = entry.id in selectedIds

        b.textTrackNumber.text = String.format(Locale.US, "%02d", row.number)
        b.textTitle.text = entry.title ?: entry.displayName
        b.textSubtitle.text = listOfNotNull(entry.artist, entry.album)
            .filter { it.isNotBlank() }.joinToString(" · ")
        b.textDuration.text = formatDuration(entry.durationMs)

        // Selected outranks activated in the row background, so both can be set.
        b.root.isActivated = entry.id == nowPlayingId
        b.root.isSelected = multiSelectMode && selected
        b.textTrackNumber.isActivated = entry.id == nowPlayingId
        b.textTitle.isActivated = entry.id == nowPlayingId

        bindArt(b, entry)

        b.checkboxSelect.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        b.checkboxSelect.setOnCheckedChangeListener(null)
        b.checkboxSelect.isChecked = selected
        b.checkboxSelect.setOnCheckedChangeListener { _, checked ->
            setSelected(entry, checked)
            b.root.isSelected = checked
        }

        b.dragHandle.visibility = if (dragEnabled && !multiSelectMode) View.VISIBLE else View.GONE
        b.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        b.root.setOnClickListener {
            if (multiSelectMode) b.checkboxSelect.isChecked = !b.checkboxSelect.isChecked
            else onClick(entry)
        }
        b.root.setOnLongClickListener {
            if (!multiSelectMode) onLongPress(entry)
            true
        }
    }

    private fun bindArt(b: ItemPlaylistEntryBinding, entry: PlaylistEntryEntity) {
        val art = entry.artPath?.let { loadArt(it) }
        if (art != null) b.imageArt.setImageBitmap(art)
        else b.imageArt.setImageDrawable(placeholderFor(b.root.context))
    }

    private fun setSelected(entry: PlaylistEntryEntity, checked: Boolean) {
        if (checked) selectedIds.add(entry.id) else selectedIds.remove(entry.id)
        onSelectionChanged()
    }

    override fun getItemCount(): Int = rows.size

    private var placeholder: HatchDrawable? = null

    /** One shared instance: it is stateless and every thumbnail draws it the same. */
    private fun placeholderFor(context: Context): HatchDrawable =
        placeholder ?: HatchDrawable(context.applicationContext, small = true).also { placeholder = it }

    private fun loadArt(path: String): Bitmap? {
        artCache.get(path)?.let { return it }
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        artCache.put(path, bmp)
        return bmp
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        private const val ART_CACHE_SLOTS = 40
        private const val TYPE_HEADER = 0
        private const val TYPE_TRACK = 1
        private const val UNKNOWN_GROUP = "Unknown"
    }
}
