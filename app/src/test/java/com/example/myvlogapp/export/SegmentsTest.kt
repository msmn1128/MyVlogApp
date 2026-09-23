package com.example.myvlogapp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本数が多いときの区切りごとの書き出し（Segments.kt）の組み立て */
class SegmentsTest {

    @Test
    fun upToTheLimit_isASingleSegment() {
        // 境目以下は従来どおり1回で書き出す
        assertEquals(listOf(0..0), planSegments(1))
        assertEquals(listOf(0 until SEGMENT_MAX_CLIPS), planSegments(SEGMENT_MAX_CLIPS))
    }

    @Test
    fun moreThanTheLimit_isSplitInOrderWithoutGapsOrOverlaps() {
        assertEquals(listOf(0..9, 10..19, 20..24), planSegments(25, maxPerSegment = 10))
        assertEquals(listOf(0..9, 10..10), planSegments(11, maxPerSegment = 10))
    }

    @Test
    fun concatList_quotesPathsAndEscapesSingleQuotes() {
        assertEquals(
            "ffconcat version 1.0\nfile '/a/seg_0.mov'\nfile '/a/it'\\''s.mov'\n",
            concatListText(listOf("/a/seg_0.mov", "/a/it's.mov"))
        )
    }

    @Test
    fun audioPlanForSegment_slicesClipsAndKeepsTheTitleSfxOnlyWhereTheTitleIs() {
        val plan = AudioPlan(needsTitleSfxInput = true, clipHasRealAudio = listOf(true, false, true, false))

        val first = plan.forSegment(0..1, includesTitle = true)
        assertTrue(first.needsTitleSfxInput)
        assertEquals(1, first.clipInputOffset)
        assertTrue(first.hasRealAudio(0))
        assertFalse(first.hasRealAudio(1))

        // 2つ目以降の区切りにはタイトルカードが無いので、効果音の-iも無い。添字は0から振り直す
        val second = plan.forSegment(2..3, includesTitle = false)
        assertFalse(second.needsTitleSfxInput)
        assertEquals(0, second.clipInputOffset)
        assertTrue(second.hasRealAudio(0))
        assertFalse(second.hasRealAudio(1))
    }
}
