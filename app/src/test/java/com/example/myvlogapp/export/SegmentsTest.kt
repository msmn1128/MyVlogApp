package com.example.myvlogapp.export

import org.junit.Assert.assertEquals
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
}
