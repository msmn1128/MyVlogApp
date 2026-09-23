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

    /**
     * PlaybackController のうち、TimelineStore から見える状態だけを同じ規則で持つ偽物。
     * プレイリストの中身と自動遷移は持たない（ここでのテストの対象外）。
     */
    private class FakePlayback(private val clips: () -> List<VlogClip>) : TimelinePlayback {
        override var selectedIndexValue = 0
        override var positionMsValue = 0L
        var isPaused = true
        /** 実物と同じく、選択中クリップのミュートから決める */
        var volume = 1f

        override fun select(index: Int) {
            val target = clips().getOrNull(index) ?: return
            selectedIndexValue = index
            applyVolume()
            seekAndPause(target.startMs)
        }

        override fun setSelectedIndex(index: Int) { selectedIndexValue = index }
        override fun setPositionMs(positionMs: Long) { positionMsValue = positionMs }
        override fun seekWithoutPause(positionMs: Long) { positionMsValue = positionMs }

        override fun seekAndPause(positionMs: Long) {
            isPaused = true
            positionMsValue = positionMs
        }

        override fun pause() { isPaused = true }

        override fun applyVolume() {
            volume = if (clips().getOrNull(selectedIndexValue)?.isMuted == true) 0f else 1f
        }

        override fun rebuildPlaylist(clips: List<VlogClip>) = Unit
        override fun insertIntoPlaylist(insertions: List<Pair<Int, VlogClip>>) = Unit
        override fun moveItem(from: Int, to: Int) { selectedIndexValue = to }
        override fun removeItem(index: Int) = Unit

        override fun clearItems() {
            selectedIndexValue = 0
            positionMsValue = 0L
        }
    }

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
