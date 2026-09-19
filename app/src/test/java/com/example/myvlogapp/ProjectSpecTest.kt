package com.example.myvlogapp

import com.example.myvlogapp.data.ProjectsMigration
import com.example.myvlogapp.data.projectsMigrationFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanReplaceWithProjectTest {

    @Test
    fun aProjectWithReadableClipsCanBeLoaded() {
        assertTrue(canReplaceWithProject(loaded = 5, dropped = 0))
    }

    @Test
    fun partiallyReadableProjectCanBeLoaded() {
        assertTrue(canReplaceWithProject(loaded = 3, dropped = 2))
    }

    @Test
    fun aProjectWhoseClipsAreAllGoneIsRefusedToProtectTheCurrentTimeline() {
        assertFalse(canReplaceWithProject(loaded = 0, dropped = 4))
    }

    @Test
    fun anEmptyProjectCanStillBeLoaded() {
        // 保存自体が空（読めなかった動画も無い）なら、そのまま読み出せる
        assertTrue(canReplaceWithProject(loaded = 0, dropped = 0))
    }
}

class ProjectMessageTest {

    @Test
    fun loadedMessageAlwaysMentionsUndo() {
        assertEquals("読み出しました（もとに戻すで読み出す前へ戻ります）", projectLoadedMessage(dropped = 0))
        // 一部が見つからないときも、もとに戻せることを案内する
        assertEquals(
            "読み出しました（2 件の動画は見つかりませんでした）。もとに戻すで読み出す前へ戻ります",
            projectLoadedMessage(dropped = 2)
        )
    }

    @Test
    fun unreadableMessageSaysNothingWasReplaced() {
        val message = projectUnreadableMessage(dropped = 3)
        assertTrue(message.contains("3 件とも見つからない"))
        assertTrue(message.contains("読み出しませんでした"))
    }
}

class ProjectsMigrationTest {

    @Test
    fun nothingToMoveWhenTheOldStoreHasNoProjects() {
        assertEquals(ProjectsMigration.NOTHING, projectsMigrationFor(legacyExists = false, currentExists = false))
        assertEquals(ProjectsMigration.NOTHING, projectsMigrationFor(legacyExists = false, currentExists = true))
    }

    @Test
    fun copiesThenRemovesWhenOnlyTheOldStoreHasThem() {
        assertEquals(
            ProjectsMigration.COPY_AND_REMOVE,
            projectsMigrationFor(legacyExists = true, currentExists = false)
        )
    }

    @Test
    fun theNewStoreWinsWhenBothHaveThem() {
        // 新しい側を正とする（新しい側に書いた保存を、古い側の中身で上書きしない）
        assertEquals(
            ProjectsMigration.REMOVE_LEGACY_ONLY,
            projectsMigrationFor(legacyExists = true, currentExists = true)
        )
    }
}
