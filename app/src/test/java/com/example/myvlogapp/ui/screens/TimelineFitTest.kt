package com.example.myvlogapp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** タイムライン欄を中身が収まるまで広げる量（TimelineFit.kt） */
class TimelineFitTest {

    @Test
    fun noExtraWhenTheContentAlreadyFits() {
        // 比率1あたり1000px、比率0.40で400pxの欄に、中身は350px
        assertEquals(0f, timelineExtraWeight(350, 1000f, 0.40f, 0.20f), 0f)
    }

    @Test
    fun addsJustTheShortfall() {
        // 中身が500pxなら比率0.50が要る。いまの0.40に0.10を上乗せする
        assertEquals(0.10f, timelineExtraWeight(500, 1000f, 0.40f, 0.20f), 1e-6f)
    }

    @Test
    fun neverTakesMoreThanTheCap() {
        // 大きすぎる文字サイズでも、削る側（プレビュー・ひとこと欄）の下限は残す
        assertEquals(0.20f, timelineExtraWeight(2_000, 1000f, 0.40f, 0.20f), 0f)
    }

    @Test
    fun noExtraBeforeTheFirstMeasurement() {
        assertEquals(0f, timelineExtraWeight(2_000, 0f, 0.40f, 0.20f), 0f)
        // 削れる余地が無い（キーボードを開いて削る側が下限まで来ている）ときも上乗せしない
        assertEquals(0f, timelineExtraWeight(2_000, 1000f, 0.40f, -0.05f), 0f)
    }
}
