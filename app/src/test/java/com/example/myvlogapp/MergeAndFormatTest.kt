package com.example.myvlogapp

import com.example.myvlogapp.export.creationTimeMetadata
import com.example.myvlogapp.export.fileNameDate
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
    fun uniqueSaveName_keepsANewNameAsIs() {
        assertEquals("旅行", uniqueSaveName("旅行", emptyList()))
        assertEquals("旅行", uniqueSaveName("旅行", listOf("別の名前", "旅行2")))
    }

    @Test
    fun uniqueSaveName_appendsTheFirstFreeNumberForATypedName() {
        assertEquals("旅行 (1)", uniqueSaveName("旅行", listOf("旅行")))
        assertEquals("旅行 (2)", uniqueSaveName("旅行", listOf("旅行", "旅行 (1)")))
        // 途中が空いていれば、そこを使う（消した番号の再利用）
        assertEquals("旅行 (1)", uniqueSaveName("旅行", listOf("旅行", "旅行 (2)")))
    }

    @Test
    fun uniqueSaveName_treatsNamesAsCaseAndSpaceSensitive() {
        // 完全一致だけを重複とみなす（「旅行」と「旅行 」は別の名前）
        assertEquals("abc", uniqueSaveName("abc", listOf("ABC")))
        assertEquals("旅行", uniqueSaveName("旅行", listOf("旅行 ")))
    }

    @Test
    fun uniqueSaveName_putsTheNumberBeforeTheSuffixNotAfterIt() {
        // 書き出しファイル名はこちらを使う。拡張子は連番の外側に来ること
        assertEquals("Vlog_2026-09-20.mp4", uniqueSaveName("Vlog_2026-09-20", emptyList(), ".mp4"))
        assertEquals(
            "Vlog_2026-09-20 (1).mp4",
            uniqueSaveName("Vlog_2026-09-20", listOf("Vlog_2026-09-20.mp4"), ".mp4")
        )
        assertEquals(
            "Vlog_2026-09-20 (2).mp4",
            uniqueSaveName(
                "Vlog_2026-09-20",
                listOf("Vlog_2026-09-20.mp4", "Vlog_2026-09-20 (1).mp4"),
                ".mp4"
            )
        )
    }

    @Test
    fun uniqueSaveName_ignoresNamesThatOnlyMatchWithoutTheSuffix() {
        // 拡張子まで含めた完全一致だけが重複。連番なしの名前と衝突させない
        assertEquals(
            "Vlog_2026-09-20.mp4",
            uniqueSaveName("Vlog_2026-09-20", listOf("Vlog_2026-09-20"), ".mp4")
        )
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
            assertEquals("2026-03-19T09:30:00Z", creationTimeMetadata(1_773_912_600_123L))
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
            val date = fileNameDate(1_773_912_600_123L)
            assertEquals("2026-03-19", date)
            // ファイル名に使えない文字（スラッシュ・コロンなど）を含まない
            assertEquals(false, Regex("[\\\\/:*?\"<>|]").containsMatchIn(date))

            // 日付をまたぐ瞬間はローカルの日付に従う（UTC 16:00 = JST 翌日 01:00）
            assertEquals("2026-03-20", fileNameDate(1_773_936_000_000L))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
