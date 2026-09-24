package com.example.myvlogapp.ui.screens

import android.net.Uri
import com.example.myvlogapp.data.GalleryVideo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

// 動画を選ぶ画面（GalleryPicker.kt）のうち、画面を組み立てずに確かめられる部分

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
