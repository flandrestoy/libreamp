package dev.libreamp.player.ui.playlist

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** Drag-only reorder (no swipe-to-delete; multi-select handles deletion). */
class PlaylistTouchCallback(
    private val adapter: PlaylistAdapter,
    private val onDragFinished: (fromPos: Int, toPos: Int) -> Unit
) : ItemTouchHelper.Callback() {

    private var dragStartPos = -1
    private var dragEndPos = -1

    override fun isLongPressDragEnabled(): Boolean = false
    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        if (!adapter.dragEnabled || adapter.multiSelectMode) return makeMovementFlags(0, 0)
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (dragStartPos == -1) dragStartPos = from
        dragEndPos = to
        adapter.moveItemVisually(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (dragStartPos != -1 && dragEndPos != -1 && dragStartPos != dragEndPos) {
            onDragFinished(dragStartPos, dragEndPos)
        }
        dragStartPos = -1
        dragEndPos = -1
    }
}
