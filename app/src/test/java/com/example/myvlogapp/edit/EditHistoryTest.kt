package com.example.myvlogapp.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 履歴の積み方・まとめ方・取り出し方。
 *
 * ViewModelから切り出したことで、時計を差し替えられるようになった部分
 * （[HISTORY_COALESCE_MS]をまたぐ/またがないの境界）までテストできる。
 */
class EditHistoryTest {

    /** テスト内で進められる時計。実機の SystemClock.elapsedRealtime の代わり */
    private var now = 0L
    private fun history(limit: Int = HISTORY_LIMIT) =
        EditHistory<String>(elapsedMs = { now }, limit = limit)

    @Test
    fun nothingToUndoAtTheStart() {
        val history = history()
        assertFalse(history.canUndo.value)
        assertFalse(history.canRedo.value)
        assertNull(history.undo("now"))
        assertNull(history.redo("now"))
    }

    @Test
    fun undoReturnsTheStateRecordedBeforeTheEdit() {
        val history = history()
        history.record("A")
        assertTrue(history.canUndo.value)
        assertEquals("A", history.undo("B"))
        assertFalse(history.canUndo.value)
    }

    @Test
    fun redoGoesBackToTheStateUndoWasCalledFrom() {
        val history = history()
        history.record("A")
        history.undo("B")
        assertTrue(history.canRedo.value)
        assertEquals("B", history.redo("A"))
        assertFalse(history.canRedo.value)
    }

    @Test
    fun aNewEditAfterUndoDropsTheRedoBranch() {
        val history = history()
        history.record("A")
        history.undo("B")
        assertTrue(history.canRedo.value)

        history.record("A2")
        assertFalse(history.canRedo.value)
    }

    // --- まとめ（coalescing） ---------------------------------------------------------

    @Test
    fun sameTagWithinTheWindowIsCoalescedIntoOneEntry() {
        val history = history()
        history.record("A", EditTag.Trim(clipIndex = 0))
        now += HISTORY_COALESCE_MS - 1
        history.record("B", EditTag.Trim(clipIndex = 0))

        // 1件だけ積まれているので、1回のundoで最初の状態まで戻る
        assertEquals("A", history.undo("C"))
        assertFalse(history.canUndo.value)
    }

    @Test
    fun theWindowIsMeasuredFromTheLastEditNotTheFirst() {
        val history = history()
        history.record("A", EditTag.Trim(clipIndex = 0))
        // まとめられるたびに起点が更新されるので、間隔が窓未満なら何回続いても1件のまま
        repeat(5) {
            now += HISTORY_COALESCE_MS - 1
            history.record("x$it", EditTag.Trim(clipIndex = 0))
        }
        assertEquals("A", history.undo("C"))
        assertFalse(history.canUndo.value)
    }

    @Test
    fun sameTagAfterTheWindowIsRecordedSeparately() {
        val history = history()
        history.record("A", EditTag.Trim(clipIndex = 0))
        now += HISTORY_COALESCE_MS
        history.record("B", EditTag.Trim(clipIndex = 0))

        assertEquals("B", history.undo("C"))
        assertEquals("A", history.undo("B"))
    }

    @Test
    fun differentTagsAreNeverCoalescedEvenAtTheSameInstant() {
        val history = history()
        history.record("A", EditTag.Trim(clipIndex = 0))
        history.record("B", EditTag.Trim(clipIndex = 1))
        history.record("C", EditTag.TrimMove(clipIndex = 1))
        history.record("D", EditTag.Text(clipIndex = 1, segmentIndex = 0))
        history.record("E", EditTag.Text(clipIndex = 1, segmentIndex = 1))

        listOf("E", "D", "C", "B", "A").forEach { expected ->
            assertEquals(expected, history.undo("current"))
        }
    }

    @Test
    fun anUntaggedEditIsAlwaysItsOwnEntry() {
        val history = history()
        history.record("A")
        history.record("B")
        assertEquals("B", history.undo("C"))
        assertEquals("A", history.undo("B"))
    }

    @Test
    fun undoResetsCoalescingSoTheNextEditIsNotMergedIntoTheOldOne() {
        val history = history()
        history.record("A", EditTag.Trim(clipIndex = 0))
        history.undo("B")
        // 直前と同じタグ・同じ時刻でも、undoを挟んだらまとめない
        history.record("A", EditTag.Trim(clipIndex = 0))
        assertTrue(history.canUndo.value)
        assertEquals("A", history.undo("B"))
    }

    // --- 上限 -------------------------------------------------------------------------

    @Test
    fun theOldestEntryIsDroppedOnceTheLimitIsReached() {
        val history = history(limit = 3)
        listOf("A", "B", "C", "D").forEach { history.record(it) }

        // 上限3件なので、最も古い"A"は落ちている
        assertEquals("D", history.undo("E"))
        assertEquals("C", history.undo("D"))
        assertEquals("B", history.undo("C"))
        assertFalse(history.canUndo.value)
    }

    @Test
    fun updateAllRewritesBothDirectionsWithoutChangingTheirCount() {
        val history = history()
        history.record("a")
        history.record("b")
        // もとに戻す側に "a"、やり直す側に（戻す直前の状態の）"c" が残る
        assertEquals("b", history.undo("c"))

        history.updateAll { it.uppercase() }

        assertEquals("C", history.redo("x"))
        assertEquals("x", history.undo("y"))  // 書き換えた後に積んだものはそのまま
        assertEquals("A", history.undo("z"))
        assertNull(history.undo("w"))
    }

    @Test
    fun clearDropsBothDirections() {
        val history = history()
        history.record("A")
        history.undo("B")
        history.clear()

        assertFalse(history.canUndo.value)
        assertFalse(history.canRedo.value)
        assertNull(history.undo("B"))
        assertNull(history.redo("B"))
    }
}
