package com.example.myvlogapp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 書き出しに要る空き容量の見積もり（ExportSpace.kt） */
class ExportSpaceTest {

    @Test
    fun outputIsEstimatedFromTheBitrates() {
        // 映像12Mbps＋音声128kbps で10秒 → (12,000,000 + 128,000) / 8 × 10 = 15,160,000バイト
        assertEquals(15_160_000L, estimatedOutputBytes(10_000L))
    }

    @Test
    fun segmentedExportNeedsRoomForTheIntermediatesToo() {
        val oneMinute = 60_000L
        val single = requiredFreeBytes(oneMinute, segmented = false)
        val segmented = requiredFreeBytes(oneMinute, segmented = true)

        // 1回: 作業フォルダの動画＋ギャラリーへのコピー（＋余裕）
        assertEquals(estimatedOutputBytes(oneMinute) * 2 + 200L * 1024 * 1024, single)
        // 区切り: さらに中間ファイル（映像12Mbps＋無圧縮の音声 44.1kHz×2ch×16bit）が加わる
        val intermediates = oneMinute * (12_000_000L + 44_100L * 2 * 16) / 8 / 1000
        assertEquals(single + intermediates, segmented)
    }

    @Test
    fun messageShowsSizesInGigabytesOrMegabytes() {
        val mb = 1024L * 1024
        // 1GB以上はGB（小数1桁）、1GB未満はMBで出す（「0.1GB」だと差が読み取りにくい）
        val message = notEnoughSpaceMessage(requiredBytes = 1536 * mb, availableBytes = 150 * mb)
        assertTrue(message, message.contains("約1.5GB必要"))
        assertTrue(message, message.contains("使える空きは150MB"))
    }

    @Test
    fun noSpaceErrorsAreRecognized() {
        assertTrue(isNoSpaceError("Error writing trailer: No space left on device"))
        assertTrue(isNoSpaceError("write failed: ENOSPC (No space left on device)"))
        assertFalse(isNoSpaceError("Invalid data found when processing input"))
        assertFalse(isNoSpaceError(null))
    }
}
