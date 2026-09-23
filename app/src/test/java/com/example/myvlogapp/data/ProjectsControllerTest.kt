package com.example.myvlogapp.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.edit.FakePlayback
import com.example.myvlogapp.edit.TimelineStore
import com.example.myvlogapp.testClip

/**
 * 一時保存の窓口（[ProjectsController]）。
 *
 * 保存先は記憶の中だけで動く偽物、タイムラインは本物の[TimelineStore]（再生側は偽物）で組み立て、
 * 「いつ保存・読み出しを断るか」と、読み出しでタイムラインがどう変わるかを確かめる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsControllerTest {

    /** 記憶の中だけで動く保存先。読み出しは[pendingLoad]で途中に止められる */
    private class FakeRepository : ProjectRepository {
        val saved = mutableListOf<Pair<String, List<VlogClip>>>()
        val deleted = mutableListOf<Long>()
        var saveResult: String? = "保存名"
        var overwriteResult = true
        var loadResult: RestoredClips? = null
        /** 設定すると、読み出しはこれが完了するまで返らない（読み出し中の割り込みを再現する） */
        var pendingLoad: CompletableDeferred<Unit>? = null

        override suspend fun list() = listOf(SavedProject(1L, "旅行", 0L, 1, 1_000L))
        override suspend fun save(name: String, clips: List<VlogClip>): String? {
            saved += name to clips
            return saveResult
        }
        override suspend fun overwrite(id: Long, clips: List<VlogClip>) = overwriteResult
        override suspend fun load(id: Long): RestoredClips? {
            pendingLoad?.await()
            return loadResult
        }
        override suspend fun delete(id: Long) { deleted += id }
    }

    private val scope = TestScope()
    private val repository = FakeRepository()
    private val messages = mutableListOf<String>()
    private var adding = false
    private var loadedCallbacks = 0

    private lateinit var timeline: TimelineStore
    private val playback = FakePlayback { timeline.current }

    private fun controller(vararg current: VlogClip): ProjectsController {
        timeline = TimelineStore(playback, elapsedMs = { 0L }, sendMessage = { messages += it }, onUrisReleased = {})
        timeline.replaceAll(current.toList(), record = false)
        timeline.clearHistory()
        return ProjectsController(
            repository = repository,
            scope = scope,
            timeline = timeline,
            isAdding = { adding },
            sendMessage = { messages += it }
        )
    }

    // --- 保存 --------------------------------------------------------------------------

    @Test
    fun savesTheCurrentTimelineAndRefreshesTheList() {
        val projects = controller(testClip(id = 1L))

        projects.save("旅行")
        scope.advanceUntilIdle()

        assertEquals(listOf("旅行"), repository.saved.map { it.first })
        assertEquals(listOf(1L), repository.saved.single().second.map { it.id })
        assertEquals(listOf("「保存名」を保存しました"), messages)
        assertEquals(1, projects.projects.value.size)
    }

    @Test
    fun blankNameFallsBackToTheSavedTime() {
        val projects = controller(testClip())

        projects.save("   ")
        scope.advanceUntilIdle()

        // 「9/23 14:30」のような日時の名前になる（空の名前では保存しない）
        val name = repository.saved.single().first
        assertTrue(name, Regex("""\d{1,2}/\d{1,2} \d{2}:\d{2}""").matches(name))
    }

    @Test
    fun refusesToSaveWhileAddingOrWhenEmpty() {
        val projects = controller(testClip())
        adding = true
        projects.save("途中")

        val empty = controller()
        adding = false
        empty.save("空")
        scope.advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
        assertEquals(
            listOf("動画を読み込み中です。終わってからもう一度お試しください", "保存できる編集内容がありません"),
            messages
        )
    }

    @Test
    fun tellsTheLimitWhenTheRepositoryIsFull() {
        repository.saveResult = null
        val projects = controller(testClip())

        projects.save("旅行")
        scope.advanceUntilIdle()

        assertEquals(listOf("保存は${ClipStore.MAX_PROJECTS}件までです。不要なものを削除してください"), messages)
    }

    @Test
    fun overwriteReportsSuccessOrFailure() {
        val projects = controller(testClip())

        projects.overwrite(1L, "旅行")
        scope.advanceUntilIdle()
        repository.overwriteResult = false
        projects.overwrite(2L, "消えた保存")
        scope.advanceUntilIdle()

        assertEquals(listOf("「旅行」に上書きしました", "この保存は上書きできませんでした"), messages)
    }

    // --- 読み出し ----------------------------------------------------------------------

    @Test
    fun loadReplacesTheTimelineAndCanBeUndone() {
        val projects = controller(testClip(id = 1L))
        repository.loadResult = RestoredClips(listOf(testClip(id = 10L), testClip(id = 11L)), dropped = 0)

        projects.load(1L, onLoaded = { loadedCallbacks++ })
        scope.advanceUntilIdle()

        assertEquals(listOf(10L, 11L), timeline.current.map { it.id })
        assertEquals(1, loadedCallbacks)
        assertEquals(listOf("読み出しました（もとに戻すで読み出す前へ戻ります）"), messages)

        // 読み出す前へ「もとに戻す」で戻れる
        timeline.undo()
        assertEquals(listOf(1L), timeline.current.map { it.id })
    }

    @Test
    fun loadIsRefusedWhenNoVideoOfTheSaveCanBeOpened() {
        val projects = controller(testClip(id = 1L))
        repository.loadResult = RestoredClips(emptyList(), dropped = 2)

        projects.load(1L, onLoaded = { loadedCallbacks++ })
        scope.advanceUntilIdle()

        // 作業中のタイムラインを空にしてしまわないよう、入れ替えない
        assertEquals(listOf(1L), timeline.current.map { it.id })
        assertEquals(0, loadedCallbacks)
        assertTrue(messages.single(), messages.single().contains("2 件とも見つからない"))
        assertFalse(timeline.canUndo.value)
    }

    @Test
    fun loadTellsWhenTheSaveCannotBeRead() {
        val projects = controller(testClip())
        repository.loadResult = null

        projects.load(1L, onLoaded = {})
        scope.advanceUntilIdle()

        assertEquals(listOf("この保存は読み出せませんでした"), messages)
    }

    @Test
    fun loadIsAbandonedIfAddingStartsWhileReading() {
        val projects = controller(testClip(id = 1L))
        repository.loadResult = RestoredClips(listOf(testClip(id = 10L)), dropped = 0)
        repository.pendingLoad = CompletableDeferred()

        projects.load(1L, onLoaded = { loadedCallbacks++ })
        scope.advanceUntilIdle()
        // 保存内の動画を開いて確かめている間に、動画の追加が始まった
        adding = true
        repository.pendingLoad!!.complete(Unit)
        scope.advanceUntilIdle()

        // 入れ替えると、あとから読み込み終えた動画が読み出した内容に混ざるので、入れ替えない
        assertEquals(listOf(1L), timeline.current.map { it.id })
        assertEquals(0, loadedCallbacks)
        assertEquals(listOf("動画を読み込み中です。終わってからもう一度お試しください"), messages)
    }

    // --- 削除 --------------------------------------------------------------------------

    @Test
    fun deleteRemovesAndRefreshesTheList() {
        val projects = controller(testClip())

        projects.delete(7L)
        scope.advanceUntilIdle()

        assertEquals(listOf(7L), repository.deleted)
        assertEquals(1, projects.projects.value.size)
    }
}
