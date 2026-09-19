package com.example.myvlogapp

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VlogClipJsonTest {

    @Before
    fun stubUriParse() {
        // fromJson は Uri.parse を呼ぶ。JVMではAndroidのUriが動かないのでモックに差し替える
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk<Uri>(relaxed = true)
    }

    @After
    fun clearMocks() = unmockkAll()

    @Test
    fun reliabilityFlagSurvivesSaveAndRestore() {
        val reliable = VlogClip.fromJson(testClip(shotAtReliable = true).toJson(), id = 1L)
        val unreliable = VlogClip.fromJson(testClip(shotAtReliable = false).toJson(), id = 2L)

        assertTrue(reliable.shotAtReliable)
        assertFalse(unreliable.shotAtReliable)
    }

    @Test
    fun saveDataWithoutTheKeyIsTreatedAsUnreliable() {
        // 確かさを持たせる前の保存データ。追加時の手がかりが足りず追加時刻で代用した値が
        // 混ざっている可能性があるので、次回の復元時に取り直させる
        val legacy = testClip().toJson().apply { remove(VlogClipKeys.SHOT_AT_RELIABLE) }

        assertFalse(VlogClip.fromJson(legacy, id = 1L).shotAtReliable)
    }

    @Test
    fun saveDataFromWhenResolutionWasStoredStillRestores() {
        // 解像度(width/height)を持っていた頃の保存データ。もう読まないキーが
        // 余分に入っていても、復元は素通りできること
        val legacy = testClip(timeText = "09:00").toJson()
            .put("width", 1920)
            .put("height", 1080)

        val restored = VlogClip.fromJson(legacy, id = 1L)
        assertEquals("09:00", restored.timeText)
    }

    @Test
    fun brokenTrimRangeIsNormalisedInsteadOfCrashingLater() {
        // 保存データが壊れていて end < start のまま復元すると、波形をタップした瞬間に
        // coerceIn(min > max) で落ちる。読み込み時に「0 <= start <= end <= 尺」へ直す
        val broken = testClip(durationMs = 10_000L, startMs = 0L, endMs = 10_000L).toJson()
            .put(VlogClipKeys.START_MS, 8_000L)
            .put(VlogClipKeys.END_MS, 3_000L)

        val restored = VlogClip.fromJson(broken, id = 1L)
        assertEquals(8_000L, restored.startMs)
        assertEquals(8_000L, restored.endMs)
        assertTrue(restored.startMs <= restored.endMs)
    }

    @Test
    fun trimRangeBeyondTheClipLengthIsPulledBackInside() {
        val broken = testClip(durationMs = 5_000L, startMs = 0L, endMs = 5_000L).toJson()
            .put(VlogClipKeys.START_MS, -100L)
            .put(VlogClipKeys.END_MS, 9_999_999L)

        val restored = VlogClip.fromJson(broken, id = 1L)
        assertEquals(0L, restored.startMs)
        assertEquals(5_000L, restored.endMs)
    }

    @Test
    fun aValidTrimRangeIsLeftUntouched() {
        val json = testClip(durationMs = 10_000L, startMs = 2_000L, endMs = 7_500L).toJson()

        val restored = VlogClip.fromJson(json, id = 1L)
        assertEquals(2_000L, restored.startMs)
        assertEquals(7_500L, restored.endMs)
    }

    @Test
    fun savedTimeTextIsKeptAsIs() {
        // 復元では、保存された時刻をそのまま読む（取り直しは、確かでないものだけをViewModelが行う）
        val json: JSONObject = testClip(timeText = "09:00", dateText = "2026/09/19", shotAtReliable = true).toJson()

        val restored = VlogClip.fromJson(json, id = 1L)
        assertEquals("09:00", restored.timeText)
        assertEquals("2026/09/19", restored.dateText)
    }
}
