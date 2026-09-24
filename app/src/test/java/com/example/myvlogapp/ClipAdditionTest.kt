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

    @Test
    fun theSameVideoInADifferentUriFormIsSkippedAsAlreadyAdded() {
        // ギャラリー（MediaStore）とファイル選択（SAF）で同じ動画のURIが違っても、鍵が同じなら追加済み。
        // 読み込むものは選ばれた形のまま返す（保存するURIは変えない）
        val keys = mapOf("saf/cam.mp4" to "media:7", "saf/new.mp4" to "media:8")
        assertEquals(
            AdditionPlan(toLoad = listOf("saf/new.mp4"), alreadyAdded = 1, overLimit = 0),
            planAddition(
                listOf("saf/cam.mp4", "saf/new.mp4"), existing = setOf("media:7"), currentCount = 1,
                keyOf = keys::getValue
            )
        )
    }

    @Test
    fun theSameVideoChosenFromBothPlacesAtOnceIsLoadedOnce() {
        val keys = mapOf("media/7" to "media:7", "saf/cam.mp4" to "media:7")
        assertEquals(
            AdditionPlan(toLoad = listOf("media/7"), alreadyAdded = 0, overLimit = 0),
            planAddition(
                listOf("media/7", "saf/cam.mp4"), existing = emptySet<String>(), currentCount = 0,
                keyOf = keys::getValue
            )
        )
    }
}
