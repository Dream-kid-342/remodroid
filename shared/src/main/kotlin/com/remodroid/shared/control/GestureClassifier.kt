package com.remodroid.shared.control

import kotlin.math.hypot

/** One pointer sample in normalised video coordinates. */
data class PointerSample(val point: NormalizedPoint, val timeMs: Long)

sealed interface ClassifiedGesture {
    data class TapGesture(val point: NormalizedPoint) : ClassifiedGesture
    data class LongPressGesture(val point: NormalizedPoint, val durationMs: Long) : ClassifiedGesture
    data class SwipeGesture(val path: List<NormalizedPoint>, val durationMs: Long) : ClassifiedGesture
}

/**
 * Converts a completed pointer trace (finger or mouse drag) into a gesture for an Android host.
 * Movement is measured in view pixels so the slop feels the same at any zoom level.
 */
object GestureClassifier {
    const val LONG_PRESS_MS = 500L
    const val MIN_SWIPE_MS = 80L

    fun classify(
        samples: List<PointerSample>,
        videoWidthPx: Float,
        videoHeightPx: Float,
        slopPx: Float,
    ): ClassifiedGesture? {
        if (samples.isEmpty()) return null
        val first = samples.first()
        val last = samples.last()
        val duration = (last.timeMs - first.timeMs).coerceAtLeast(0)
        val maxDistance = samples.maxOf {
            hypot((it.point.x - first.point.x) * videoWidthPx, (it.point.y - first.point.y) * videoHeightPx)
        }
        if (maxDistance < slopPx) {
            return if (duration >= LONG_PRESS_MS) {
                ClassifiedGesture.LongPressGesture(first.point, duration.coerceAtMost(ControlCodec.MAX_GESTURE_MS))
            } else {
                ClassifiedGesture.TapGesture(first.point)
            }
        }
        return ClassifiedGesture.SwipeGesture(
            path = downsample(samples.map { it.point }, ControlCodec.MAX_SWIPE_POINTS),
            durationMs = duration.coerceIn(MIN_SWIPE_MS, ControlCodec.MAX_GESTURE_MS),
        )
    }

    /** Keeps the first and last points and evenly spaced points in between. */
    fun downsample(points: List<NormalizedPoint>, max: Int): List<NormalizedPoint> {
        if (points.size <= max) return points.ifEmpty { points }
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { i -> points[(i * step).toInt().coerceAtMost(points.size - 1)] }
            .toMutableList().also { it[it.lastIndex] = points.last() }
    }

    fun toCommand(gesture: ClassifiedGesture, seq: Long): InputCommand = when (gesture) {
        is ClassifiedGesture.TapGesture -> Tap(seq, gesture.point)
        is ClassifiedGesture.LongPressGesture -> LongPress(seq, gesture.point, gesture.durationMs)
        is ClassifiedGesture.SwipeGesture -> Swipe(seq, gesture.path, gesture.durationMs)
    }
}
