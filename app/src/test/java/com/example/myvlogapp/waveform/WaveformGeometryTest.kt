package com.example.myvlogapp.waveform

import com.example.myvlogapp.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class FitWaveformViewportTest {

    @Test
    fun zeroDurationGivesEmptyRange() {
        assertEquals(0L..0L, fitWaveformViewport(0L, 0L, 0L))
    }

    @Test
    fun largeSelectionKeepsWholeView() {
        // 全体の60%以上を選んでいるときはズームしない
        assertEquals(0L..10_000L, fitWaveformViewport(0L, 7_000L, 10_000L))
    }

    @Test
    fun smallSelectionZoomsWithMarginOfHalfTheSelection() {
        assertEquals(17_500L..27_500L, fitWaveformViewport(20_000L, 25_000L, 60_000L))
    }

    @Test
    fun tinySelectionStillGetsMinimumWindow() {
        // 余白300msずつだと1100msしかない → 最低3000msまで両側へ広げる
        assertEquals(28_750L..31_750L, fitWaveformViewport(30_000L, 30_500L, 60_000L))
    }

    @Test
    fun windowNearStartIsShiftedRightNotShrunk() {
        assertEquals(0L..3_000L, fitWaveformViewport(0L, 1_000L, 60_000L))
    }

    @Test
    fun windowNearEndIsShiftedLeftNotShrunk() {
        assertEquals(57_000L..60_000L, fitWaveformViewport(59_000L, 60_000L, 60_000L))
    }
}

class ClampHandleMsTest {

    @Test
    fun startHandleStopsMinTrimBeforeEnd() {
        assertEquals(3_700L, clampHandleMs(TrimHandle.Start, 5_000L, 1_000L, 4_000L, 10_000L))
        assertEquals(0L, clampHandleMs(TrimHandle.Start, -50L, 1_000L, 4_000L, 10_000L))
        assertEquals(2_000L, clampHandleMs(TrimHandle.Start, 2_000L, 1_000L, 4_000L, 10_000L))
    }

    @Test
    fun endHandleStopsMinTrimAfterStartAndAtDuration() {
        assertEquals(1_300L, clampHandleMs(TrimHandle.End, 1_100L, 1_000L, 4_000L, 10_000L))
        assertEquals(10_000L, clampHandleMs(TrimHandle.End, 20_000L, 1_000L, 4_000L, 10_000L))
    }

    @Test
    fun clipShorterThanMinTrimDoesNotThrow() {
        // coerceIn(min, max) は min > max で例外になる。可動域が無いときは端に倒れるだけ
        assertEquals(200L, clampHandleMs(TrimHandle.End, 100L, 0L, 200L, 200L))
        assertEquals(0L, clampHandleMs(TrimHandle.Start, 50L, 0L, 200L, 200L))
    }
}

class ComputeMoveSpanTest {

    @Test
    fun keepsSpanWidthInsideVideo() {
        assertEquals(MoveSpanResult(2_000L, 5_000L), computeMoveSpan(2_000L, 0L, 3_000L, 10_000L))
        assertEquals(MoveSpanResult(7_000L, 10_000L), computeMoveSpan(9_000L, 0L, 3_000L, 10_000L))
        assertEquals(MoveSpanResult(0L, 3_000L), computeMoveSpan(-100L, 0L, 3_000L, 10_000L))
    }

    @Test
    fun spanWiderThanVideoDoesNotThrow() {
        assertEquals(MoveSpanResult(0L, 500L), computeMoveSpan(100L, 0L, 500L, 200L))
    }
}

class HitTestTrimTest {

    // 表示範囲 0〜10000ms を 0〜1000px に対応させる（1px = 10ms）
    private val track = TrackMetrics(left = 0f, right = 1_000f, viewStartMs = 0L, viewEndMs = 10_000L)
    private val grabRadius = 30f
    private val texts = listOf(TextSegment(0L), TextSegment(4_000L))

    private fun hit(downX: Float, startMs: Long = 2_000L, endMs: Long = 6_000L) =
        hitTestTrim(downX, track, startMs, endMs, texts, grabRadius)

    @Test
    fun nearStartHandle() = assertEquals(TrimGrab.Handle(TrimHandle.Start), hit(205f))

    @Test
    fun nearEndHandle() = assertEquals(TrimGrab.Handle(TrimHandle.End), hit(595f))

    @Test
    fun nearSplitLine() = assertEquals(TrimGrab.Split(1), hit(405f))

    @Test
    fun farFromEverythingIsBody() = assertEquals(TrimGrab.Body, hit(800f))

    @Test
    fun handleWinsATieAgainstSplitLine() {
        // つまみ(410px)と区切り(400px)の中間 = どちらも5px。つまみを優先する
        assertEquals(TrimGrab.Handle(TrimHandle.Start), hit(405f, startMs = 4_100L, endMs = 9_000L))
    }

    @Test
    fun xToMsClampsButExtrapolatedDoesNot() {
        assertEquals(0L, track.xToMs(-50f))
        assertEquals(10_000L, track.xToMs(1_200f))
        assertEquals(-1_000L, track.extrapolatedMs(-100f))
    }
}

/**
 * つまみ／区間ごと移動が今の表示範囲からはみ出したときのパン。
 * 表示幅（ズーム倍率）は変えず、窓だけを指の位置へずらす。
 */
class PannedViewportTest {

    private val locked = 2_000L..6_000L

    @Test
    fun aPositionInsideTheViewportLeavesItAlone() {
        assertEquals(locked, pannedViewport(locked, ms = 3_000L, durationMs = 20_000L))
    }

    @Test
    fun goingPastTheLeftEdgeSlidesTheWindowKeepingItsWidth() {
        assertEquals(1_000L..5_000L, pannedViewport(locked, ms = 1_000L, durationMs = 20_000L))
    }

    @Test
    fun goingPastTheRightEdgeSlidesTheWindowKeepingItsWidth() {
        assertEquals(4_000L..8_000L, pannedViewport(locked, ms = 8_000L, durationMs = 20_000L))
    }

    @Test
    fun theWindowNeverStartsBeforeTheBeginningOfTheVideo() {
        assertEquals(0L..4_000L, pannedViewport(locked, ms = -5_000L, durationMs = 20_000L))
    }

    @Test
    fun theWindowNeverEndsAfterTheEndOfTheVideo() {
        assertEquals(6_000L..10_000L, pannedViewport(locked, ms = 30_000L, durationMs = 10_000L))
    }
}

/** 端に張り付いたまま指を止めているときの、1ティックあたりの進み */
class EdgeScrollTickTest {

    @Test
    fun withoutZoomTheTickIsTwoPercentOfTheWholeClip() {
        assertEquals(200L, edgeScrollTickMs(viewport = null, durationMs = 10_000L))
    }

    @Test
    fun whileZoomedInTheTickFollowsTheVisibleSpanNotTheClipLength() {
        // 表示中が3000msなら、尺が10分あってもその2%で進む
        assertEquals(60L, edgeScrollTickMs(viewport = 1_000L..4_000L, durationMs = 600_000L))
    }

    @Test
    fun theTickIsNeverZero() {
        // 2%が0msへ丸まると、端に張り付いても一切進まなくなる
        assertEquals(1L, edgeScrollTickMs(viewport = 0L..10L, durationMs = 10L))
    }
}
