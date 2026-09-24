package com.example.myvlogapp.edit

import com.example.myvlogapp.MIN_TEXT_SEGMENT_MS
import com.example.myvlogapp.TextSegment
import com.example.myvlogapp.VideoMeta
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.testClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * タイムラインの編集と、もとに戻す / やり直す。
 *
 * 再生側（ExoPlayer）は[FakePlayback]に差し替えてある。TimelineStore が再生側に
 * 何をどの順で頼んだかではなく、頼んだ結果の状態（選択位置・再生位置・音量）で確かめる。
 */
class TimelineStoreTest {

    /** テスト内で進められる時計。実機の SystemClock.elapsedRealtime の代わり */
    private var now = 0L
    private val messages = mutableListOf<String>()
    private lateinit var store: TimelineStore
    private val playback = FakePlayback { store.current }

    /** [clips] を前回の続きとして復元した直後の状態（履歴は空、先頭を選択）にする */
    private fun storeWith(vararg clips: VlogClip): TimelineStore {
        store = TimelineStore(
            playback = playback,
            elapsedMs = { now },
            sendMessage = { messages += it },
            onUrisReleased = {}
        )
        store.replaceAll(clips.toList(), record = false)
        store.clearHistory()
        return store
    }

    private val selected get() = store.selectedClip!!

    // --- ミュート ---------------------------------------------------------------------

    @Test
    fun undoingMuteOfTheSelectedClipRestoresPlaybackVolume() {
        val store = storeWith(testClip(id = 1))

        store.toggleClipMute(1)
        assertEquals(0f, playback.volume)

        store.undo()
        assertFalse(selected.isMuted)
        assertEquals(1f, playback.volume)

        store.redo()
        assertTrue(selected.isMuted)
        assertEquals(0f, playback.volume)
    }

    // --- 区切りの移動 -------------------------------------------------------------------

    /** 素材は0〜10秒、トリムで3〜9秒だけを使い、6秒でひとことを切り替えるクリップ */
    private fun trimmedSplitClip() = testClip(
        id = 1,
        durationMs = 10_000L,
        startMs = 3_000L,
        endMs = 9_000L,
        texts = listOf(TextSegment(0L, "前半"), TextSegment(6_000L, "後半"))
    )

    @Test
    fun splitCannotMoveBeforeTrimStartAndSeeksToWhereItStopped() {
        val store = storeWith(trimmedSplitClip())

        store.moveSplit(1, 0L)

        val expected = 3_000L + MIN_TEXT_SEGMENT_MS
        assertEquals(expected, selected.texts[1].startMs)
        assertEquals(expected, playback.positionMsValue)
    }

    @Test
    fun splitCannotMovePastTrimEnd() {
        val store = storeWith(trimmedSplitClip())

        store.moveSplit(1, 20_000L)

        assertEquals(9_000L - MIN_TEXT_SEGMENT_MS, selected.texts[1].startMs)
    }

    @Test
    fun splitCannotMovePastThePreviousSplit() {
        val store = storeWith(
            testClip(
                id = 1,
                texts = listOf(TextSegment(0L, "a"), TextSegment(4_000L, "b"), TextSegment(6_000L, "c"))
            )
        )

        store.moveSplit(2, 0L)

        assertEquals(4_000L + MIN_TEXT_SEGMENT_MS, selected.texts[2].startMs)
    }

    // --- ひとこと ---------------------------------------------------------------------

    @Test
    fun updateTextRewritesOnlyTheSegmentUnderThePlayhead() {
        val store = storeWith(
            testClip(id = 1, texts = listOf(TextSegment(0L, "a"), TextSegment(5_000L, "b")))
        )
        playback.setPositionMs(6_000L)

        store.updateText("B")

        assertEquals(listOf("a", "B"), selected.texts.map { it.text })
    }

    @Test
    fun sameTextDoesNotRecordHistory() {
        val store = storeWith(testClip(id = 1, texts = listOf(TextSegment(0L, "a"))))

        store.updateText("a")

        assertFalse(store.canUndo.value)
    }

    @Test
    fun typingIsUndoneTogetherUnlessThereIsAPause() {
        val store = storeWith(testClip(id = 1))

        store.updateText("あ")
        now += HISTORY_COALESCE_MS - 1
        store.updateText("あい")
        now += HISTORY_COALESCE_MS
        store.updateText("あいう")

        store.undo()
        assertEquals("あい", selected.texts[0].text)
        store.undo()
        assertEquals("", selected.texts[0].text)
    }

    @Test
    fun splittingLeavesTheSecondHalfEmptyAndCanBeUndoneAndRedone() {
        val store = storeWith(testClip(id = 1, texts = listOf(TextSegment(0L, "a"))))
        playback.setPositionMs(5_000L)

        store.splitTextAtPlayhead()
        assertEquals(listOf(0L to "a", 5_000L to ""), selected.texts.map { it.startMs to it.text })
        assertEquals(5_000L, playback.positionMsValue)

        store.undo()
        assertEquals(1, selected.texts.size)

        store.redo()
        assertEquals(2, selected.texts.size)
    }

    @Test
    fun splittingTooCloseToAnEdgeIsRefusedWithAMessage() {
        val store = storeWith(testClip(id = 1))
        playback.setPositionMs(MIN_TEXT_SEGMENT_MS - 1)

        store.splitTextAtPlayhead()

        assertEquals(1, selected.texts.size)
        assertEquals(listOf("区切る位置が端に寄りすぎています"), messages)
        assertFalse(store.canUndo.value)
    }

    // --- トリミング -------------------------------------------------------------------

    @Test
    fun unchangedTrimDoesNotRecordHistory() {
        val store = storeWith(testClip(id = 1, startMs = 1_000L, endMs = 3_000L))

        // つまみを端の限界で止めたまま動かしたとき・同じ長さのプリセットを押したとき
        store.updateTrim(1_000L, 3_000L)
        store.applyTrimPreset(2_000L)

        assertFalse(store.canUndo.value)
    }

    @Test
    fun trimPresetKeepsTheCurrentStartWhenItFits() {
        val store = storeWith(testClip(id = 1, durationMs = 10_000L, startMs = 3_000L, endMs = 9_000L))

        store.applyTrimPreset(2_000L)

        assertEquals(3_000L to 5_000L, selected.startMs to selected.endMs)
    }

    @Test
    fun trimPresetNearTheEndMovesTheStartBackInsteadOfGettingShorter() {
        // 終わりで切っていた頃は、残り0.5秒のところで「2s」を押すと0.5秒になっていた
        val store = storeWith(testClip(id = 1, durationMs = 10_000L, startMs = 9_500L, endMs = 10_000L))

        store.applyTrimPreset(2_000L)

        assertEquals(8_000L to 10_000L, selected.startMs to selected.endMs)
    }

    @Test
    fun trimPresetLongerThanTheVideoSelectsTheWholeVideo() {
        val store = storeWith(testClip(id = 1, durationMs = 1_500L, startMs = 500L, endMs = 1_500L))

        store.applyTrimPreset(4_000L)

        assertEquals(0L to 1_500L, selected.startMs to selected.endMs)
    }

    // --- 動画の追加 -------------------------------------------------------------------

    /**
     * ギャラリーとファイル選択では、同じ動画でもURIの形が違う。反映の直前の確かめでも
     * 呼び出し元の鍵で比べ、読み込み中に別の経路から先に入った同じ動画は入れない
     */
    @Test
    fun insertSkipsTheSameVideoAddedFromTheOtherPickerMeanwhile() {
        val fromGallery = testClip(id = 1)
        val fromFilePicker = testClip(id = 2)
        val other = testClip(id = 3)
        val keys = mapOf(fromGallery.uri to "media:42", fromFilePicker.uri to "media:42", other.uri to "media:7")
        val store = storeWith(fromGallery)

        val result = store.insertByShotAt(listOf(fromFilePicker, other)) { keys.getValue(it) }

        assertEquals(1, result.alreadyPresent)
        assertEquals(listOf(1L, 3L), store.current.map { it.id })
    }

    // --- 一覧の入れ替え ---------------------------------------------------------------

    @Test
    fun undoingALoadOfTheSameVideosStillRebuildsTheTiles() {
        val clip = testClip(id = 1)
        val store = storeWith(clip)
        // 同じ動画だけの一時保存を読み出す（idは新しく振られる）
        store.replaceAll(listOf(clip.copy(id = 2)), record = true)
        val before = store.replacementCount.value

        store.undo()

        assertEquals(1L, selected.id)
        assertEquals(before + 1, store.replacementCount.value)
    }

    @Test
    fun undoingATextEditDoesNotRebuildTheTiles() {
        val store = storeWith(testClip(id = 1))
        store.updateText("旅行")
        val before = store.replacementCount.value

        store.undo()

        assertEquals(before, store.replacementCount.value)
    }

    // --- 撮影時刻の取り直し -----------------------------------------------------------

    @Test
    fun refreshedShotTimeSurvivesUndo() {
        val store = storeWith(testClip(id = 1, timeText = "00:00", shotAtReliable = false))
        store.updateText("旅行")

        store.applyRefreshedShotTimes(
            mapOf(1L to VideoMeta("12:34", "2026/09/01", 1L, shotAtReliable = true, durationMs = 10_000L))
        )
        store.undo()

        assertEquals("", selected.texts[0].text)
        assertEquals("12:34", selected.timeText)
        assertTrue(selected.shotAtReliable)
        assertTrue(selected.shotAtRefreshed)
    }
}
