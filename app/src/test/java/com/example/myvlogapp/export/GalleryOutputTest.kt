package com.example.myvlogapp.export

import org.junit.Assert.assertEquals
import org.junit.Test

// 書き出した動画をギャラリーへ保存するときの名前とメタデータ（GalleryOutput.kt）

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
