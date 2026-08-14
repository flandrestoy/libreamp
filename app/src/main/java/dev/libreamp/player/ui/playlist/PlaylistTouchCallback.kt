package dev.libreamp.player.ui.playlist

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Drag-only reorder (no swipe-to-delete; multi-select handles deletion).
 *
 * Dragging is how membership is edited: a track dropped between a group's header and its
 * footer joins that group, and one dropped past the footer leaves it. Nothing here needs
 * to decide that — it only has to keep the row list legal, and let
 * [PlaylistAdapter.currentArrangement] read the structure back off the result.
 */
class PlaylistTouchCallback(
    private val adapter: PlaylistAdapter,
    private val onDragFinished: () -> Unit
) : ItemTouchHelper.Callback() {

    private var moved = false

    override fun isLongPressDragEnabled(): Boolean = false
    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (!adapter.dragEnabled || adapter.multiSelectMode) return makeMovementFlags(0, 0)
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return makeMovementFlags(0, 0)
        // Tracks move freely; a group moves only while collapsed, when it is a single row.
        // A footer is the container's edge, not something the user owns.
        val draggable = adapter.rowIsTrack(position) || adapter.rowIsCollapsedHeader(position)
        if (!draggable) return makeMovementFlags(0, 0)
        return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

        // Groups do not nest, so a dragged group may not come to rest inside another one.
        // A track has no such restriction: every slot means something for a track, which is
        // the point of resolving membership positionally.
        if (adapter.rowIsCollapsedHeader(from) && adapter.positionIsInsideGroup(to)) return false

        adapter.moveRow(from, to)
        moved = true
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (moved) onDragFinished()
        moved = false
    }
}
