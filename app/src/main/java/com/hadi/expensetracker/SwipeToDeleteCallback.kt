package com.hadi.expensetracker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Lets the user swipe an expense row left or right to delete it. While swiping, a rounded
 * red panel with a trash icon is drawn behind the row so the gesture reads clearly before
 * the item is actually removed (removal itself is reported via [onSwipeToDelete]).
 */
class SwipeToDeleteCallback(
    recyclerView: RecyclerView,
    private val onSwipeToDelete: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(recyclerView.context, R.color.swipe_delete_bg)
    }

    private val icon = ContextCompat.getDrawable(recyclerView.context, R.drawable.ic_delete)?.mutate()?.apply {
        setTint(ContextCompat.getColor(recyclerView.context, R.color.red))
    }

    private val cornerRadius = recyclerView.resources.getDimension(R.dimen.corner_card)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        if (pos < 0) return
        onSwipeToDelete(pos)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        if (dX != 0f) {
            val top = itemView.top.toFloat()
            val bottom = itemView.bottom.toFloat()

            // Size the panel to exactly the revealed gap (not the whole row) and round every
            // corner of it — a static full-width rect only looks rounded at its far ends,
            // which for a normal partial swipe just reads as a flat rectangular edge.
            val rect = if (dX > 0) {
                RectF(itemView.left.toFloat(), top, itemView.left + dX, bottom)
            } else {
                RectF(itemView.right + dX, top, itemView.right.toFloat(), bottom)
            }
            c.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)

            icon?.let { drawable ->
                val margin = (itemView.height - drawable.intrinsicHeight) / 2
                val iconTop = itemView.top + margin
                val iconBottom = iconTop + drawable.intrinsicHeight
                if (dX > 0) {
                    val iconLeft = itemView.left + margin
                    drawable.setBounds(iconLeft, iconTop, iconLeft + drawable.intrinsicWidth, iconBottom)
                } else {
                    val iconRight = itemView.right - margin
                    drawable.setBounds(iconRight - drawable.intrinsicWidth, iconTop, iconRight, iconBottom)
                }
                if (rect.width() > drawable.intrinsicWidth + margin) {
                    drawable.draw(c)
                }
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
