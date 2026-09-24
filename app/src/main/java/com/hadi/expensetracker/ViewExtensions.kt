package com.hadi.expensetracker

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

fun View.applyPressAnimation(pressedScale: Float = 0.96f) {
    setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().cancel()
                view.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(70)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().cancel()
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
        }
        false
    }
}

fun ViewGroup.animateChildrenIn(staggerMs: Long = 18L) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        child.animate().cancel()
        child.alpha = 0f
        child.scaleX = 0.98f
        child.scaleY = 0.98f
        child.translationY = 10f
        child.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setStartDelay(i * staggerMs)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}

fun View.animateValidationError() {
    animate().cancel()
    val start = translationX
    animate().translationX(start - 7f).setDuration(45).withEndAction {
        animate().translationX(start + 7f).setDuration(55).withEndAction {
            animate().translationX(start - 4f).setDuration(45).withEndAction {
                animate().translationX(start).setDuration(45).start()
            }.start()
        }.start()
    }.start()
}

fun View.animateContentIn(direction: Int = 0) {
    animate().cancel()
    alpha = 0.72f
    scaleX = 0.985f
    scaleY = 0.985f
    translationX = 14f * direction
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .translationX(0f)
        .setDuration(190)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

fun View.animateContentOut(direction: Int = 0, onEnd: () -> Unit) {
    animate().cancel()
    animate()
        .alpha(0.72f)
        .scaleX(0.985f)
        .scaleY(0.985f)
        .translationX(-14f * direction)
        .setDuration(110)
        .withEndAction { onEnd() }
        .start()
}
