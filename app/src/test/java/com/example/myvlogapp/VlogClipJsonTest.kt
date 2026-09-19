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
    fun savedTimeTextIsKeptAsIs() {
        // 復元では、保存された時刻をそのまま読む（取り直しは、確かでないものだけをViewModelが行う）
        val json: JSONObject = testClip(timeText = "09:00", dateText = "2026/09/19", shotAtReliable = true).toJson()

        val restored = VlogClip.fromJson(json, id = 1L)
        assertEquals("09:00", restored.timeText)
        assertEquals("2026/09/19", restored.dateText)
    }
}
