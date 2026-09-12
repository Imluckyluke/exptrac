package com.hadi.expensetracker

import android.view.MotionEvent
import android.view.View
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
