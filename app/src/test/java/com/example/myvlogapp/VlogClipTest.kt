package com.example.myvlogapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class VlogClipTest {

    private val threeSegments = listOf(
        TextSegment(0L, "a"),
        TextSegment(3_000L, "b"),
        TextSegment(6_000L, "c")
    )

    // --- 尺・有効判定 ---------------------------------------------------------------

    @Test
    fun trimmedDuration_isEndMinusStart() {
        assertEquals(3_000L, testClip(startMs = 2_000L, endMs = 5_000L).trimmedDurationMs)
    }

    @Test
    fun trimmedDuration_neverNegative() {
        assertEquals(0L, testClip(startMs = 5_000L, endMs = 2_000L).trimmedDurationMs)
    }

    @Test
    fun isValid_requiresPositiveDurationAndRange() {
        assertTrue(testClip(startMs = 0L, endMs = 1L).isValid)
        assertFalse(testClip(startMs = 5_000L, endMs = 5_000L).isValid)
        assertFalse(testClip(durationMs = 0L, startMs = 0L, endMs = 0L).isValid)
    }

    // --- ひとことの区間 ---------------------------------------------------------------

    @Test
    fun textIndexAt_picksLastSegmentStartingAtOrBeforePosition() {
        val clip = testClip(texts = threeSegments)
        assertEquals(0, clip.textIndexAt(0L))
        assertEquals(0, clip.textIndexAt(2_999L))
        assertEquals(1, clip.textIndexAt(3_000L))
        assertEquals(2, clip.textIndexAt(9_000L))
    }

    @Test
    fun textAt_returnsTextOfSegmentAtPosition() {
        val clip = testClip(texts = threeSegments)
        assertEquals("a", clip.textAt(100L))
        assertEquals("c", clip.textAt(7_000L))
    }

    @Test
    fun visibleTextSpans_fullRange_yieldsEverySegment() {
        val spans = testClip(texts = threeSegments).visibleTextSpans()
        assertEquals(
            listOf(
                TextSpan(1, 0L, 3_000L, "a"),
                TextSpan(2, 3_000L, 6_000L, "b"),
                TextSpan(3, 6_000L, 10_000L, "c")
            ),
            spans
        )
    }

    @Test
    fun visibleTextSpans_trimmedHead_keepsTheSegmentShowingAtTrimStart() {
        // 4000msから始めると、その時点で出ているのは2番目（3000〜）。1番目は落ちる
        val spans = testClip(texts = threeSegments, startMs = 4_000L, endMs = 8_000L).visibleTextSpans()
        assertEquals(
            listOf(
                TextSpan(2, 4_000L, 6_000L, "b"),
                TextSpan(3, 6_000L, 8_000L, "c")
            ),
            spans
        )
    }

    @Test
    fun visibleTextSpans_trimmedTail_dropsSegmentsThatNeverPlay() {
        val spans = testClip(texts = threeSegments, startMs = 0L, endMs = 5_000L).visibleTextSpans()
        assertEquals(
            listOf(
                TextSpan(1, 0L, 3_000L, "a"),
                TextSpan(2, 3_000L, 5_000L, "b")
            ),
            spans
        )
    }

    // --- 区切りの近傍判定 ---------------------------------------------------------------

    @Test
    fun splitPointNear_withinToleranceFindsIt() {
        // 尺10秒 → 許容幅は 10000/40 = 250ms
        val clip = testClip(texts = threeSegments)
        assertEquals(3_000L, clip.splitPointNear(3_100L))
        assertEquals(6_000L, clip.splitPointNear(5_800L))
    }

    @Test
    fun splitPointNear_outsideToleranceIsNull() {
        assertNull(testClip(texts = threeSegments).splitPointNear(4_500L))
    }

    @Test
    fun splitPointNear_withoutSplitsIsNull() {
        assertNull(testClip().splitPointNear(0L))
    }

    // --- 並び替えキー・ミュート ---------------------------------------------------------

    @Test
    fun sortKey_prefersShotAtMillis() {
        assertEquals(12_345L, testClip(shotAtMillis = 12_345L).sortKeyMs)
    }

    @Test
    fun sortKey_fallsBackToDateAndTimeText() {
        val expected = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).parse("2026/03/04 12:34")!!.time
        assertEquals(
            expected,
            testClip(shotAtMillis = 0L, dateText = "2026/03/04", timeText = "12:34").sortKeyMs
        )
    }

    @Test
    fun sortKey_brokenTextGoesLast() {
        assertEquals(
            Long.MAX_VALUE,
            testClip(shotAtMillis = 0L, dateText = "???", timeText = "??").sortKeyMs
        )
    }

    @Test
    fun isSilentInExport_isTrueWhenEitherClipOrTimelineIsMuted() {
        assertFalse(testClip(isMuted = false).isSilentInExport(timelineMuted = false))
        assertTrue(testClip(isMuted = true).isSilentInExport(timelineMuted = false))
        assertTrue(testClip(isMuted = false).isSilentInExport(timelineMuted = true))
        assertTrue(testClip(isMuted = true).isSilentInExport(timelineMuted = true))
    }
}
