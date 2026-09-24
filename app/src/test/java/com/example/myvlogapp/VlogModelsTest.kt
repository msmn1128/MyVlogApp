package com.example.myvlogapp

import org.junit.Assert.assertEquals
import org.junit.Test

// VlogModels.kt のうち、VlogClip自身（VlogClipTest / VlogClipJsonTest）以外の純粋な処理

class MergeByShotAtTest {

    private fun clip(id: Long, shotAt: Long) = testClip(id = id, shotAtMillis = shotAt)

    @Test
    fun insertsNewClipsIntoShotOrderWithoutTouchingExistingOrder() {
        val a = clip(1, 100)
        val c = clip(3, 300)
        val b = clip(2, 200)
        val d = clip(4, 400)

        // addedは撮影順とは無関係な並びで渡される
        val (merged, insertions) = mergeByShotAt(listOf(a, c), listOf(d, b))

        assertEquals(listOf(a, b, c, d), merged)
        assertEquals(listOf(1 to b, 3 to d), insertions)
    }

    @Test
    fun insertionsReplayedInOrderReproduceTheMergedList() {
        val current = listOf(clip(1, 100), clip(3, 300), clip(5, 500))
        val added = listOf(clip(6, 600), clip(0, 50), clip(4, 400), clip(2, 200))

        val (merged, insertions) = mergeByShotAt(current, added)

        // ExoPlayerのプレイリストへは、この(index, clip)を昇順に1件ずつ入れて再現する
        val replayed = current.toMutableList()
        insertions.forEach { (index, clip) -> replayed.add(index, clip) }
        assertEquals(merged, replayed)
        assertEquals(listOf<Long>(0, 1, 2, 3, 4, 5, 6), merged.map { it.id })
    }

    @Test
    fun sameShotTimeGoesAfterTheExistingClip() {
        val existing = clip(1, 100)
        val added = clip(2, 100)
        assertEquals(listOf(existing, added), mergeByShotAt(listOf(existing), listOf(added)).first)
    }

    @Test
    fun mergesIntoEmptyTimeline() {
        val x = clip(1, 200)
        val y = clip(2, 100)
        val (merged, insertions) = mergeByShotAt(emptyList(), listOf(x, y))
        assertEquals(listOf(y, x), merged)
        assertEquals(listOf(0 to y, 1 to x), insertions)
    }
}

/**
 * 区間ごと移動でトリム範囲とひとことの区切りを「ひとかたまり」で動かすための量。
 * 区切りを1つずつ丸めていた頃は、トリムより手前に残った区切りが先頭付近へ潰れ、
 * 相対位置が失われて「もとに戻す」以外で復元できなくなっていた。
 */
class ClampTimelineShiftTest {

    private val texts = listOf(
        TextSegment(0L, "a"),
        TextSegment(2_000L, "b"),
        TextSegment(5_000L, "c")
    )

    @Test
    fun aMoveThatKeepsEverySplitInsideTheVideoIsNotClamped() {
        assertEquals(-1_000L, clampTimelineShift(texts, requested = -1_000L, durationMs = 10_000L))
        assertEquals(3_000L, clampTimelineShift(texts, requested = 3_000L, durationMs = 10_000L))
    }

    @Test
    fun movingLeftStopsWhereTheFirstSplitReachesTheMinimumSegmentLength() {
        // 先頭の区間は絶対位置0のまま動かないので、次の区切りは400msより手前へは行けない
        assertEquals(-1_600L, clampTimelineShift(texts, requested = -5_000L, durationMs = 10_000L))
    }

    @Test
    fun movingRightStopsWhereTheLastSplitReachesTheEndOfTheVideo() {
        assertEquals(5_000L, clampTimelineShift(texts, requested = 9_999L, durationMs = 10_000L))
    }

    @Test
    fun aClipWithoutSplitsCanMoveAsFarAsTheTrimRangeAllows() {
        assertEquals(
            -9_999L,
            clampTimelineShift(listOf(TextSegment()), requested = -9_999L, durationMs = 10_000L)
        )
    }

    @Test
    fun saveDataThatAlreadyBreaksTheRuleIsNeverPushedTheOtherWay() {
        // すでに下限を割っている区切り。許容範囲に0（動かさない）を含めていないと、
        // 左へ動かそうとしたのに右へ飛んでしまう
        val broken = listOf(TextSegment(0L, "a"), TextSegment(100L, "b"))

        assertEquals(0L, clampTimelineShift(broken, requested = -500L, durationMs = 10_000L))
        assertEquals(500L, clampTimelineShift(broken, requested = 500L, durationMs = 10_000L))
    }
}
