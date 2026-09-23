package com.example.myvlogapp

import org.junit.Assert.assertEquals
import org.junit.Test

/** 動画を追加するときの振り分け（[planAddition]）。URIの代わりに文字列で確かめる */
class ClipAdditionTest {

    @Test
    fun newVideosAreLoadedInTheOrderTheyWereChosen() {
        assertEquals(
            AdditionPlan(toLoad = listOf("c", "a", "b"), alreadyAdded = 0, overLimit = 0),
            planAddition(listOf("c", "a", "b"), existing = emptySet(), currentCount = 0)
        )
    }

    @Test
    fun videosAlreadyInTheTimelineAreSkippedAndCounted() {
        assertEquals(
            AdditionPlan(toLoad = listOf("b"), alreadyAdded = 1, overLimit = 0),
            planAddition(listOf("a", "b"), existing = setOf("a"), currentCount = 1)
        )
    }

    @Test
    fun theSameVideoChosenTwiceIsNotReportedAsAlreadyAdded() {
        // 引き算で数えていた頃は、タイムラインに無いのに「1件は追加済み」と出ていた
        assertEquals(
            AdditionPlan(toLoad = listOf("a"), alreadyAdded = 0, overLimit = 0),
            planAddition(listOf("a", "a"), existing = emptySet(), currentCount = 0)
        )
    }

    @Test
    fun videosBeyondTheLimitAreRefusedWithoutLoading() {
        // 残り2本の枠に4本 → 選んだ順の先頭2本だけ読み、2本は読まずに断る
        assertEquals(
            AdditionPlan(toLoad = listOf("a", "b"), alreadyAdded = 0, overLimit = 2),
            planAddition(listOf("a", "b", "c", "d"), existing = emptySet(), currentCount = 8, limit = 10)
        )
    }

    @Test
    fun aFullTimelineLoadsNothing() {
        assertEquals(
            AdditionPlan(toLoad = emptyList<String>(), alreadyAdded = 1, overLimit = 2),
            planAddition(listOf("x", "a", "b"), existing = setOf("x"), currentCount = 10, limit = 10)
        )
    }
}
