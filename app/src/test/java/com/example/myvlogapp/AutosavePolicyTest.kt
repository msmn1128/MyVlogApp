package com.example.myvlogapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前回の続き（自動保存）をいつ書き換えてよいか（[AutosavePolicy]） */
class AutosavePolicyTest {

    @Test
    fun nothingIsSavedBeforeTheRestoreFinishes() {
        val policy = AutosavePolicy()
        // 復元前の空の一覧で、前回の内容を上書きしない
        assertFalse(policy.shouldSave(canUndo = true))
        assertFalse(policy.shouldSaveOnExit(canUndo = true))
    }

    @Test
    fun afterAFullRestore_everyChangeIsSaved() {
        val policy = AutosavePolicy()
        policy.onRestored(droppedCount = 0)
        // 撮影時刻の取り直し（履歴に積まれない変化）も書いてよい
        assertTrue(policy.shouldSave(canUndo = false))
        assertTrue(policy.shouldSaveOnExit(canUndo = false))
    }

    @Test
    fun afterDroppingUnreadableVideos_theSaveIsKeptUntilTheFirstEdit() {
        val policy = AutosavePolicy()
        policy.onRestored(droppedCount = 2)

        // 編集していない間（開いただけ・撮影時刻の取り直しだけ）は書き換えない。落とした動画の
        // 編集内容が保存から消えると、権限を戻しても続きが戻らなくなる
        assertFalse(policy.shouldSave(canUndo = false))
        assertFalse(policy.shouldSaveOnExit(canUndo = false))

        // 最初の編集（「もとに戻す」が押せるようになった）からは書く
        assertTrue(policy.shouldSave(canUndo = true))
        // 保留は一度解けたら戻らない（そのあと「もとに戻す」を押し切っても書く）
        assertTrue(policy.shouldSave(canUndo = false))
        assertTrue(policy.shouldSaveOnExit(canUndo = false))
    }

    @Test
    fun editingRightBeforeClosing_isStillSavedOnExit() {
        val policy = AutosavePolicy()
        policy.onRestored(droppedCount = 1)
        // 自動保存が編集の合図を受け取る前に閉じられても、閉じるときに書く
        assertTrue(policy.shouldSaveOnExit(canUndo = true))
    }
}
