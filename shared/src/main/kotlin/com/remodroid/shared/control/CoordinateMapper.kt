package com.remodroid.shared.control

import kotlin.math.min
import kotlin.math.roundToInt

/** Integer rectangle in view pixels. */
data class ViewRect(val left: Float, val top: Float, val width: Float, val height: Float)

object CoordinateMapper {
    /**
     * Where a video of [videoWidth]x[videoHeight] is drawn inside a view of [viewWidth]x[viewHeight]
     * when scaled to fit (letterboxed) and centred.
     */
    fun fitRect(videoWidth: Int, videoHeight: Int, viewWidth: Float, viewHeight: Float): ViewRect? {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
        val scale = min(viewWidth / videoWidth, viewHeight / videoHeight)
        val w = videoWidth * scale
        val h = videoHeight * scale
        return ViewRect((viewWidth - w) / 2f, (viewHeight - h) / 2f, w, h)
    }

    /** Where the video is drawn when scaled to fill the view (cropped) and centred. The rect may extend past the view. */
    fun fillRect(videoWidth: Int, videoHeight: Int, viewWidth: Float, viewHeight: Float): ViewRect? {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
        val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
        val w = videoWidth * scale
        val h = videoHeight * scale
        return ViewRect((viewWidth - w) / 2f, (viewHeight - h) / 2f, w, h)
    }

    /** Converts a touch/mouse position on the controller's view into a normalised video point, or null if outside the video. */
    fun viewToNormalized(x: Float, y: Float, videoRect: ViewRect): NormalizedPoint? {
        val nx = (x - videoRect.left) / videoRect.width
        val ny = (y - videoRect.top) / videoRect.height
        if (nx !in 0f..1f || ny !in 0f..1f) return null
        return NormalizedPoint(nx, ny)
    }

    /** Same as [viewToNormalized] but clamps to the video edge; used while a drag is in progress. */
    fun viewToNormalizedClamped(x: Float, y: Float, videoRect: ViewRect): NormalizedPoint {
        val nx = ((x - videoRect.left) / videoRect.width).coerceIn(0f, 1f)
        val ny = ((y - videoRect.top) / videoRect.height).coerceIn(0f, 1f)
        return NormalizedPoint(nx, ny)
    }

    /**
     * Maps a normalised point to the host's physical display pixels. The transmitted frame already
     * matches the display's current orientation, so only scaling is needed.
     */
    fun normalizedToDisplay(point: NormalizedPoint, displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val px = (point.x * (displayWidth - 1)).roundToInt().coerceIn(0, displayWidth - 1)
        val py = (point.y * (displayHeight - 1)).roundToInt().coerceIn(0, displayHeight - 1)
        return px to py
    }

    /**
     * Picks a capture size that keeps the display's aspect ratio, with the long edge at most
     * [maxLongEdge] and both sides even (required by most video encoders).
     */
    fun captureSize(displayWidth: Int, displayHeight: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(displayWidth, displayHeight)
        val scale = if (longEdge > maxLongEdge) maxLongEdge.toDouble() / longEdge else 1.0
        fun even(v: Double) = maxOf(2, (v.roundToInt() / 2) * 2)
        return even(displayWidth * scale) to even(displayHeight * scale)
    }
}
