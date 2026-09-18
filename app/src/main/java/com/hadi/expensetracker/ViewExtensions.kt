package com.hadi.expensetracker

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Adds a subtle "press" scale animation to any view: it shrinks slightly on touch-down
 * and springs back on release/cancel. Does not consume the touch event, so existing
 * click listeners keep working exactly as before.
 */
fun View.applyPressAnimation(pressedScale: Float = 0.95f) {
    setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(100)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
        }
        false
    }
}

/**
 * Fades and rises each direct child into place with a small stagger, for lists that are
 * built up programmatically (category legends, bank rows) instead of via a RecyclerView
 * (which already animates insertions on its own). Call once, right after the children have
 * been added to [this] ViewGroup.
 */
fun ViewGroup.animateChildrenIn(staggerMs: Long = 35L) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        child.alpha = 0f
        child.translationY = 16f
        child.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(i * staggerMs)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
