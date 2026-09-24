package com.example.myvlogapp.data

import com.example.myvlogapp.VlogClipKeys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

// 保存と復元（ClipStore.kt）のうち、SharedPreferencesを使わずに確かめられる部分

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
