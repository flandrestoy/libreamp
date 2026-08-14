package dev.libreamp.player.ui.playlist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.libreamp.player.R
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.data.db.PlaylistItem
import dev.libreamp.player.databinding.ItemPlaylistEntryBinding
import dev.libreamp.player.databinding.ItemPlaylistFooterBinding
import dev.libreamp.player.databinding.ItemPlaylistHeaderBinding
import dev.libreamp.player.ui.widget.HatchDrawable
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Renders the one-level tree as a flat row list. An expanded group becomes a header, its
 * members, and a footer; a collapsed group is its header alone, standing in for everything
 * inside it.
 *
 * The footer is not decoration. Because it is a real row, "inside the group" and "after the
 * group" are two different drop indices rather than two readings of the same one, which is
 * what lets [currentArrangement] recover membership from position without a single guess.
 */
class PlaylistAdapter(
    private val onClick: (PlaylistEntryEntity) -> Unit,
    private val onLongPress: (PlaylistEntryEntity) -> Unit,
    private val onSelectionChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onToggleCollapse: (PlaylistItem.Group) -> Unit,
    private val onGroupMenu: (PlaylistItem.Group, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        class GroupHeader(val item: PlaylistItem.Group) : Row()
        /** [number] restarts inside each group; loose tracks share one running count. */
        class Track(val entry: PlaylistEntryEntity, val number: Int, val grouped: Boolean) : Row()
        class GroupFooter(val groupId: Long) : Row()
    }

    private var items: List<PlaylistItem> = emptyList()
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

    /** Row to highlight; set from the engine's state, so guard against redundant redraws. */
    var nowPlayingId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submitItems(newItems: List<PlaylistItem>) {
        items = newItems
        rebuildRows()
    }

    fun currentTracks(): List<PlaylistEntryEntity> = rows.mapNotNull { (it as? Row.Track)?.entry }

    fun selectedEntries(): List<PlaylistEntryEntity> = currentTracks().filter { it.id in selectedIds }

    fun selectedCount(): Int = selectedIds.size

    /** Select-all doubles as clear-all once everything is already picked. */
    fun toggleSelectAll() {
        val visible = currentTracks()
        if (selectedIds.size == visible.size) selectedIds.clear()
        else selectedIds.addAll(visible.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectTracks(entries: List<PlaylistEntryEntity>) {
        selectedIds.addAll(entries.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun rebuildRows() {
        val built = mutableListOf<Row>()
        var looseNumber = 0
        for (item in items) {
            when (item) {
                is PlaylistItem.LooseTrack ->
                    built += Row.Track(item.entry, ++looseNumber, grouped = false)

                is PlaylistItem.Group -> {
                    built += Row.GroupHeader(item)
                    // A collapsed group keeps its tracks in the model but contributes no
                    // rows of its own — that is the entire difference collapse makes.
                    if (!item.group.collapsed) {
                        item.tracks.forEachIndexed { index, track ->
                            built += Row.Track(track, index + 1, grouped = true)
                        }
                        built += Row.GroupFooter(item.group.id)
                    }
                }
            }
        }
        rows = built
        notifyDataSetChanged()
    }

    // ---- drag support ----

    /** Purely visual reorder while dragging; persistence happens once on drag-end. */
    fun moveRow(from: Int, to: Int) {
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }

    /**
     * Row index of a track, or of the collapsed header standing in for it — scrolling to
     * the playing track has to land somewhere even when it is currently hidden.
     */
    fun rowPositionOf(entryId: Long): Int = rows.indexOfFirst { row ->
        when (row) {
            is Row.Track -> row.entry.id == entryId
            is Row.GroupHeader -> row.item.group.collapsed &&
                row.item.tracks.any { it.id == entryId }
            is Row.GroupFooter -> false
        }
    }

    fun rowIsTrack(position: Int): Boolean = rows.getOrNull(position) is Row.Track

    /** Only a collapsed group is a single row, so only a collapsed group can be dragged whole. */
    fun rowIsCollapsedHeader(position: Int): Boolean =
        (rows.getOrNull(position) as? Row.GroupHeader)?.item?.group?.collapsed == true

    /**
     * Groups do not nest, so a dragged group may not come to rest between an expanded
     * group's header and its footer. Landing *on* a header means landing above it, which
     * is a legal top-level slot — hence the exclusive lower bound.
     */
    fun positionIsInsideGroup(position: Int): Boolean {
        var openedAt = -1
        rows.forEachIndexed { index, row ->
            when (row) {
                is Row.GroupHeader -> openedAt = if (row.item.group.collapsed) -1 else index
                is Row.GroupFooter -> {
                    if (openedAt >= 0 && position > openedAt && position <= index) return true
                    openedAt = -1
                }
                is Row.Track -> Unit
            }
        }
        return false
    }

    /**
     * Reads the arrangement back out of the rows the user is looking at. Structure comes
     * from position — a track between a header and its footer is in that group — and is
     * handed to the repository, which writes it down as explicit membership.
     */
    fun currentArrangement(): List<PlaylistItem> {
        val arrangement = mutableListOf<PlaylistItem>()
        var openGroup: PlaylistItem.Group? = null
        var collected = mutableListOf<PlaylistEntryEntity>()

        fun closeOpenGroup() {
            openGroup?.let { arrangement += it.copy(tracks = collected) }
            openGroup = null
            collected = mutableListOf()
        }

        for (row in rows) {
            when (row) {
                is Row.GroupHeader -> {
                    closeOpenGroup()
                    if (row.item.group.collapsed) arrangement += row.item
                    else openGroup = row.item
                }

                is Row.GroupFooter -> closeOpenGroup()

                is Row.Track -> {
                    val current = openGroup
                    if (current != null) collected += row.entry
                    else arrangement += PlaylistItem.LooseTrack(row.entry)
                }
            }
        }
        closeOpenGroup()
        return arrangement
    }

    // ---- view holders ----

    class HeaderHolder(val binding: ItemPlaylistHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class FooterHolder(val binding: ItemPlaylistFooterBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * Each holder owns its placeholder. One shared instance across rows would
     * be smaller, but an ImageView takes ownership of its drawable's callback
     * and bounds, so the last row bound would quietly become the only one the
     * drawable could invalidate.
     */
    class TrackHolder(val binding: ItemPlaylistEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val placeholder = HatchDrawable(binding.root.context.applicationContext, small = true)

        /**
         * Captured straight after inflation, while the layout's own padding is still in
         * force. Swapping the row background at bind time re-applies that drawable's
         * nested insets as padding, so re-reading the view's padding afterwards would
         * compound it a little more on every rebind.
         */
        val padStart = binding.root.paddingStart
        val padTop = binding.root.paddingTop
        val padEnd = binding.root.paddingEnd
        val padBottom = binding.root.paddingBottom
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.GroupHeader -> TYPE_HEADER
        is Row.GroupFooter -> TYPE_FOOTER
        is Row.Track -> TYPE_TRACK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemPlaylistHeaderBinding.inflate(inflater, parent, false))
            TYPE_FOOTER -> FooterHolder(ItemPlaylistFooterBinding.inflate(inflater, parent, false))
            else -> TrackHolder(ItemPlaylistEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.GroupHeader -> bindHeader(holder as HeaderHolder, row.item)
            is Row.GroupFooter -> Unit // inert: it is a boundary, not content
            is Row.Track -> bindTrack(holder as TrackHolder, row)
        }
    }

    private fun bindHeader(holder: HeaderHolder, item: PlaylistItem.Group) {
        val b = holder.binding
        val context = b.root.context
        val collapsed = item.group.collapsed
        val memberIds = item.tracks.map { it.id }

        b.textGroupLabel.text = item.group.label
        b.textGroupMeta.text = if (item.tracks.isEmpty()) {
            context.getString(R.string.group_meta_empty)
        } else {
            context.getString(
                R.string.group_meta,
                context.resources.getQuantityString(
                    R.plurals.group_track_count, item.tracks.size, item.tracks.size
                ),
                formatTotalDuration(item.tracks.sumOf { it.durationMs })
            )
        }

        b.imageChevron.setImageResource(
            if (collapsed) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down
        )

        // Collapsed, the header is the only thing standing in for the track that is
        // playing inside it, so it has to carry the marker the hidden row would have.
        b.dotPlaying.visibility =
            if (collapsed && nowPlayingId != null && nowPlayingId in memberIds) View.VISIBLE
            else View.GONE

        val allSelected = memberIds.isNotEmpty() && selectedIds.containsAll(memberIds)
        b.root.isSelected = multiSelectMode && allSelected
        b.checkboxGroup.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        b.checkboxGroup.setOnCheckedChangeListener(null)
        b.checkboxGroup.isChecked = allSelected
        b.checkboxGroup.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.addAll(memberIds) else selectedIds.removeAll(memberIds.toSet())
            notifyDataSetChanged()
            onSelectionChanged()
        }

        b.btnGroupMenu.visibility = if (multiSelectMode) View.GONE else View.VISIBLE
        b.btnGroupMenu.setOnClickListener { onGroupMenu(item, it) }

        // Only a collapsed group is one row, and only one row can be dragged as a unit.
        b.dragHandleGroup.visibility =
            if (dragEnabled && !multiSelectMode && collapsed) View.VISIBLE else View.GONE
        b.dragHandleGroup.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        b.root.setOnClickListener {
            if (multiSelectMode) b.checkboxGroup.isChecked = !b.checkboxGroup.isChecked
            else onToggleCollapse(item)
        }
        b.root.setOnLongClickListener { onGroupMenu(item, b.root); true }
    }

    private fun bindTrack(holder: TrackHolder, row: Row.Track) {
        val entry = row.entry
        val b = holder.binding
        val selected = entry.id in selectedIds

        // Background first, then padding: setting a background re-applies its own insets as
        // padding, so the indent has to be written after it, from the values captured at
        // inflation rather than from whatever the view is carrying now.
        b.root.setBackgroundResource(
            if (row.grouped) R.drawable.bg_playlist_row_grouped else R.drawable.bg_playlist_row
        )
        val indent =
            if (row.grouped) b.root.resources.getDimensionPixelSize(R.dimen.group_indent) else 0
        b.root.setPaddingRelative(
            holder.padStart + indent, holder.padTop, holder.padEnd, holder.padBottom
        )

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

        bindArt(holder, entry)

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

    private fun bindArt(holder: TrackHolder, entry: PlaylistEntryEntity) {
        val art = entry.artPath?.let { loadArt(it) }
        if (art != null) holder.binding.imageArt.setImageBitmap(art)
        else holder.binding.imageArt.setImageDrawable(holder.placeholder)
    }

    private fun setSelected(entry: PlaylistEntryEntity, checked: Boolean) {
        if (checked) selectedIds.add(entry.id) else selectedIds.remove(entry.id)
        onSelectionChanged()
    }

    override fun getItemCount(): Int = rows.size

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

    /** Group totals routinely pass an hour, where a bare minute count stops being readable. */
    private fun formatTotalDuration(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, (totalSeconds % 3600) / 60, totalSeconds % 60)
        } else {
            String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }

    companion object {
        private const val ART_CACHE_SLOTS = 40
        private const val TYPE_HEADER = 0
        private const val TYPE_TRACK = 1
        private const val TYPE_FOOTER = 2
    }
}
