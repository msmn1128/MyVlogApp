package com.example.myvlogapp

import android.net.Uri
import com.example.myvlogapp.data.ClipStore
import com.example.myvlogapp.data.GalleryVideo
import com.example.myvlogapp.ui.screens.oldestFirst
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddSkipMessageTest {

    @Test
    fun nothingSkippedMeansNoMessage() {
        assertNull(addSkipMessage(alreadyAdded = 0, unreadable = 0))
    }

    @Test
    fun alreadyAddedOnly() {
        assertEquals("2 件は追加済みのためスキップしました", addSkipMessage(alreadyAdded = 2, unreadable = 0))
    }

    @Test
    fun unreadableOnlyTellsToSelectAgain() {
        assertEquals(
            "1 件は読み込めなかったので追加しませんでした（もう一度選び直してください）",
            addSkipMessage(alreadyAdded = 0, unreadable = 1)
        )
    }

    @Test
    fun overLimitTellsTheLimit() {
        assertEquals(
            "3 件は上限（100本）を超えるため追加しませんでした",
            addSkipMessage(alreadyAdded = 0, unreadable = 0, overLimit = 3)
        )
        assertEquals(
            "3 件は上限（50本）を超えるため追加しませんでした",
            addSkipMessage(alreadyAdded = 0, unreadable = 0, overLimit = 3, limit = 50)
        )
    }

    @Test
    fun theLimitIs100() {
        // 実測に基づく値（VlogConstants.MAX_CLIPS）。変えるときはメモリの実測をやり直すこと
        assertEquals(100, MAX_CLIPS)
    }

    @Test
    fun allThreeKindsAreReportedInOneMessage() {
        assertEquals(
            "1 件は追加済みのためスキップしました\n" +
                "2 件は読み込めなかったので追加しませんでした（もう一度選び直してください）\n" +
                "3 件は上限（100本）を超えるため追加しませんでした",
            addSkipMessage(alreadyAdded = 1, unreadable = 2, overLimit = 3)
        )
    }

    @Test
    fun bothAreReportedInOneMessage() {
        assertEquals(
            "2 件は追加済みのためスキップしました\n" +
                "1 件は読み込めなかったので追加しませんでした（もう一度選び直してください）",
            addSkipMessage(alreadyAdded = 2, unreadable = 1)
        )
    }
}

class OldestFirstTest {

    private fun video(name: String) = GalleryVideo(mockk<Uri>(relaxed = true), name, 1_000L)

    @Test
    fun selectedVideosAreHandedOverOldestFirst() {
        // 一覧は追加日時の新しい順（new → old）。渡す順はその逆
        val newest = video("new")
        val middle = video("mid")
        val oldest = video("old")
        val notSelected = video("skip")

        val result = oldestFirst(
            videos = listOf(newest, notSelected, middle, oldest),
            selected = setOf(oldest.uri, newest.uri, middle.uri)
        )

        assertEquals(listOf(oldest.uri, middle.uri, newest.uri), result)
    }

    @Test
    fun selectionOutsideTheListIsIgnored() {
        val listed = video("a")
        val gone = video("gone")
        assertEquals(listOf(listed.uri), oldestFirst(listOf(listed), setOf(listed.uri, gone.uri)))
    }

    @Test
    fun emptySelectionYieldsEmptyList() {
        assertEquals(emptyList<Uri>(), oldestFirst(listOf(video("a")), emptySet()))
    }
}

class UriStringsInProjectsTest {

    private fun project(vararg uris: String) = JSONObject().put(
        "clips",
        JSONArray().apply { uris.forEach { put(JSONObject().put(VlogClipKeys.URI, it)) } }
    )

    @Test
    fun collectsEveryClipUriAcrossProjects() {
        val result = ClipStore.uriStringsInProjects(
            listOf(project("content://a/1", "content://a/2"), project("content://a/2", "content://b/3"))
        )
        assertEquals(setOf("content://a/1", "content://a/2", "content://b/3"), result)
    }

    @Test
    fun brokenEntriesAreSkippedWithoutLosingTheRest() {
        val broken = JSONObject().put("clips", JSONArray().put("not an object").put(JSONObject()))
        val noClips = JSONObject().put("name", "x")

        val result = ClipStore.uriStringsInProjects(listOf(broken, noClips, project("content://ok/1")))

        assertEquals(setOf("content://ok/1"), result)
    }

    @Test
    fun noProjectsMeansNoUris() {
        assertEquals(emptySet<String>(), ClipStore.uriStringsInProjects(emptyList()))
    }
}
