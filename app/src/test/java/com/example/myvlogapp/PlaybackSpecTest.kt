package com.example.myvlogapp

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayFromWhereTest {

    private fun from(
        isLast: Boolean,
        autoAdvance: Boolean,
        position: Long,
        end: Long = 5_000L,
        ended: Boolean = false
    ) = playFromWhere(isLast, autoAdvance, position, end, ended)

    @Test
    fun midwayStopsResumeFromTheCurrentPosition() {
        assertEquals(PlayFrom.CURRENT_POSITION, from(isLast = true, autoAdvance = true, position = 2_000L))
        assertEquals(PlayFrom.CURRENT_POSITION, from(isLast = false, autoAdvance = false, position = 2_000L))
    }

    @Test
    fun afterStoppingAtTheEndOfTheLastClipPlayRestartsFromTheTimelineStart() {
        // 連続再生で最後のクリップの終わりで止まったあと、再生を押すと先頭から
        assertEquals(PlayFrom.TIMELINE_START, from(isLast = true, autoAdvance = true, position = 5_000L))
    }

    @Test
    fun aPositionJustShortOfTheEndCountsAsTheEnd() {
        // 終わりで止めた位置は、動画の実際の長さの丸めで、終端に数ミリ秒だけ届かないことがある
        assertEquals(PlayFrom.TIMELINE_START, from(isLast = true, autoAdvance = true, position = 4_900L))
        assertEquals(PlayFrom.SELECTED_CLIP_START, from(isLast = false, autoAdvance = false, position = 4_900L))
    }

    @Test
    fun aPositionClearlyBeforeTheEndIsNotTheEnd() {
        assertEquals(PlayFrom.CURRENT_POSITION, from(isLast = true, autoAdvance = true, position = 4_800L))
    }

    @Test
    fun aPlayerThatRanToItsEndCountsAsStoppedAtTheEndEvenIfThePositionIsShort() {
        // トリミング終端が動画の末尾と一致すると、位置の丸めで終端に届かないままSTATE_ENDEDになりうる
        assertEquals(
            PlayFrom.TIMELINE_START,
            from(isLast = true, autoAdvance = true, position = 4_990L, ended = true)
        )
    }

    @Test
    fun parkedAtTheEndOfAMiddleClipWithAutoAdvanceMovesOnToTheNextClip() {
        assertEquals(PlayFrom.CURRENT_POSITION, from(isLast = false, autoAdvance = true, position = 5_000L))
    }

    @Test
    fun withAutoAdvanceOffPlayReplaysTheSelectedClip() {
        // 1本ずつ見直す使い方。終わりで止まった後に再生を押すと、そのクリップをもう一度見る
        assertEquals(PlayFrom.SELECTED_CLIP_START, from(isLast = false, autoAdvance = false, position = 5_000L))
        assertEquals(PlayFrom.SELECTED_CLIP_START, from(isLast = true, autoAdvance = false, position = 5_000L))
    }

    @Test
    fun endedStateOnANonLastClipIsIgnored() {
        // STATE_ENDED は最後のクリップでだけ意味を持つ
        assertEquals(
            PlayFrom.CURRENT_POSITION,
            from(isLast = false, autoAdvance = true, position = 1_000L, ended = true)
        )
    }
}
