package com.example.myvlogapp.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class ParseCreationTimeTest {

    @Test
    fun parsesStandardFormat() {
        // 2026-03-19T09:30:00.123Z
        assertEquals(1_773_912_600_123L, parseCreationTime("20260319T093000.123Z"))
    }

    @Test
    fun toleratesMissingFractionAndMissingZ() {
        // 機種によっては小数部やZが付かない
        assertEquals(1_789_778_101_000L, parseCreationTime("20260919T003501Z"))
        assertEquals(1_789_778_101_000L, parseCreationTime("20260919T003501"))
        assertEquals(1_789_778_101_000L, parseCreationTime("20260919T003501.000Z"))
        assertEquals(1_789_778_101_100L, parseCreationTime("20260919T003501.1Z"))
    }

    @Test
    fun mp4EpochMeansUnsetAndIsRejected() {
        // 作成日時が未設定のMP4は 1904/01/01 と読める（負のミリ秒になる）
        assertNull(parseCreationTime("19040101T000000.000Z"))
    }

    @Test
    fun unixEpochIsRejectedToo() {
        assertNull(parseCreationTime("19700101T000000.000Z"))
    }

    @Test
    fun impossibleDatesAndGarbageAreNull() {
        assertNull(parseCreationTime("20261301T000000.000Z")) // 13月
        assertNull(parseCreationTime("20260919T253501.000Z")) // 25時
        assertNull(parseCreationTime(""))
        assertNull(parseCreationTime("not a date"))
    }
}

class ParseShotTimeFromFileNameTest {

    private lateinit var original: TimeZone

    @Before
    fun useTokyo() {
        original = TimeZone.getDefault()
        // ファイル名の日時は端末のローカル時刻として解釈する
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun restore() = TimeZone.setDefault(original)

    @Test
    fun screenRecordingNames() {
        // 2026-09-01 10:15:00 JST
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("Screen_Recording_20260901-101500.mp4"))
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("Screen_Recording_20260901_101500_Chrome.mp4"))
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("Screenrecorder-20260901-101500.mp4"))
    }

    @Test
    fun cameraAppNames() {
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("VID_20260901_101500.mp4"))
        // ミリ秒が続く形（Pixelのカメラ）
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("PXL_20260901_101500123.mp4"))
    }

    @Test
    fun separatedFormats() {
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("2026-09-01 10-15-00.mp4"))
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("Recording 2026.09.01_10.15.00.mov"))
        assertEquals(1_788_225_300_000L, parseShotTimeFromFileName("Screen Recording 2026-09-01 at 10.15.00.mov"))
    }

    @Test
    fun amPmIsHonored() {
        assertEquals(1_789_819_509_000L, parseShotTimeFromFileName("Screen Recording 2026-09-19 at 9.05.09 PM.mov"))
        // 12 AM は 0 時
        assertEquals(1_789_743_909_000L, parseShotTimeFromFileName("Screen Recording 2026-09-19 at 12.05.09 AM.mov"))
    }

    @Test
    fun namesWithoutATimeAreNull() {
        assertNull(parseShotTimeFromFileName("IMG_4258.MOV"))
        assertNull(parseShotTimeFromFileName("IMG-20260901-WA0001.mp4")) // 日付だけ
        assertNull(parseShotTimeFromFileName("clip15.mp4"))
        assertNull(parseShotTimeFromFileName(""))
    }

    @Test
    fun impossibleOrImplausibleDatesAreNull() {
        assertNull(parseShotTimeFromFileName("VID_20261301_101500.mp4")) // 13月
        assertNull(parseShotTimeFromFileName("VID_20260901_251500.mp4")) // 25時
        assertNull(parseShotTimeFromFileName("VID_18000901_101500.mp4")) // 1800年
        // 長い数字列（エポック秒など）の一部を日時と誤読しない
        assertNull(parseShotTimeFromFileName("1789780782123456.mp4"))
    }

    @Test
    fun firstValidDateWinsWhenSeveralAppear() {
        assertEquals(
            1_788_225_300_000L,
            parseShotTimeFromFileName("copy_of_Screen_Recording_20260901-101500_20260919-093501.mp4")
        )
    }
}
