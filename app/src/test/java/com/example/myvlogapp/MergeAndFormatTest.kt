package com.example.myvlogapp

import com.example.myvlogapp.export.VlogExporter
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeByShotAtTest {

    private fun clip(id: Long, shotAt: Long) = testClip(id = id, shotAtMillis = shotAt)

    @Test
    fun insertsNewClipsIntoShotOrderWithoutTouchingExistingOrder() {
        val a = clip(1, 100)
        val c = clip(3, 300)
        val b = clip(2, 200)
        val d = clip(4, 400)

        // addedは撮影順とは無関係な並びで渡される
        val (merged, insertions) = mergeByShotAt(listOf(a, c), listOf(d, b))

        assertEquals(listOf(a, b, c, d), merged)
        assertEquals(listOf(1 to b, 3 to d), insertions)
    }

    @Test
    fun insertionsReplayedInOrderReproduceTheMergedList() {
        val current = listOf(clip(1, 100), clip(3, 300), clip(5, 500))
        val added = listOf(clip(6, 600), clip(0, 50), clip(4, 400), clip(2, 200))

        val (merged, insertions) = mergeByShotAt(current, added)

        // ExoPlayerのプレイリストへは、この(index, clip)を昇順に1件ずつ入れて再現する
        val replayed = current.toMutableList()
        insertions.forEach { (index, clip) -> replayed.add(index, clip) }
        assertEquals(merged, replayed)
        assertEquals(listOf<Long>(0, 1, 2, 3, 4, 5, 6), merged.map { it.id })
    }

    @Test
    fun sameShotTimeGoesAfterTheExistingClip() {
        val existing = clip(1, 100)
        val added = clip(2, 100)
        assertEquals(listOf(existing, added), mergeByShotAt(listOf(existing), listOf(added)).first)
    }

    @Test
    fun mergesIntoEmptyTimeline() {
        val x = clip(1, 200)
        val y = clip(2, 100)
        val (merged, insertions) = mergeByShotAt(emptyList(), listOf(x, y))
        assertEquals(listOf(y, x), merged)
        assertEquals(listOf(0 to y, 1 to x), insertions)
    }
}

class FormattersTest {

    @Test
    fun formatSeconds_isMinutesAndZeroPaddedSeconds() {
        assertEquals("0:00", formatSeconds(0L))
        assertEquals("0:59", formatSeconds(59_999L))
        assertEquals("1:01", formatSeconds(61_000L))
        assertEquals("10:00", formatSeconds(600_000L))
    }

    @Test
    fun defaultSaveName_appendsFirstUnusedNumber() {
        val now = 1_800_000_000_000L
        val base = defaultSaveName(now, emptyList())

        assertEquals(base, defaultSaveName(now, listOf("別の名前")))
        assertEquals("$base (1)", defaultSaveName(now, listOf(base)))
        assertEquals("$base (2)", defaultSaveName(now, listOf(base, "$base (1)")))
    }
}

class CreationTimeMetadataTest {

    @Test
    fun isIso8601InUtcRegardlessOfDeviceZone() {
        val original = java.util.TimeZone.getDefault()
        try {
            // 端末のタイムゾーンに関わらず、UTCで出す（2026-03-19T09:30:00Z）
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("2026-03-19T09:30:00Z", VlogExporter.creationTimeMetadata(1_773_912_600_123L))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}

class FileNameDateTest {

    @Test
    fun isLocalDateOnlyWithoutForbiddenCharacters() {
        val original = java.util.TimeZone.getDefault()
        try {
            // 2026-03-19T09:30:00Z。端末のローカル時刻（JST=UTC+9）の日付で出す。時刻は含めない
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            val date = VlogExporter.fileNameDate(1_773_912_600_123L)
            assertEquals("2026-03-19", date)
            // ファイル名に使えない文字（スラッシュ・コロンなど）を含まない
            assertEquals(false, Regex("[\\\\/:*?\"<>|]").containsMatchIn(date))

            // 日付をまたぐ瞬間はローカルの日付に従う（UTC 16:00 = JST 翌日 01:00）
            assertEquals("2026-03-20", VlogExporter.fileNameDate(1_773_936_000_000L))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
