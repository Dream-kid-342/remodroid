package com.remodroid.shared

import com.remodroid.shared.control.ClassifiedGesture
import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.GestureClassifier
import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.shared.control.PointerSample
import com.remodroid.shared.control.Swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureClassifierTest {
    private fun s(x: Float, y: Float, t: Long) = PointerSample(NormalizedPoint(x, y), t)

    @Test
    fun `short stationary press is a tap`() {
        val g = GestureClassifier.classify(listOf(s(0.5f, 0.5f, 0), s(0.501f, 0.5f, 90)), 1000f, 2000f, 16f)
        assertIs<ClassifiedGesture.TapGesture>(g)
    }

    @Test
    fun `long stationary press is a long press`() {
        val g = GestureClassifier.classify(listOf(s(0.2f, 0.2f, 0), s(0.2f, 0.2f, 700)), 1000f, 2000f, 16f)
        assertIs<ClassifiedGesture.LongPressGesture>(g)
        assertEquals(700, g.durationMs)
    }

    @Test
    fun `movement beyond slop is a swipe with a bounded, valid path`() {
        val samples = (0..200).map { s(0.1f + it * 0.004f, 0.5f, it * 5L) }
        val g = GestureClassifier.classify(samples, 1000f, 2000f, 16f)
        assertIs<ClassifiedGesture.SwipeGesture>(g)
        assertEquals(ControlCodec.MAX_SWIPE_POINTS, g.path.size)
        assertEquals(samples.first().point, g.path.first())
        assertEquals(samples.last().point, g.path.last())
        assertTrue(ControlCodec.isWellFormed(GestureClassifier.toCommand(g, 1) as Swipe))
    }

    @Test
    fun `empty trace yields nothing`() {
        assertNull(GestureClassifier.classify(emptyList(), 1f, 1f, 1f))
    }
}
