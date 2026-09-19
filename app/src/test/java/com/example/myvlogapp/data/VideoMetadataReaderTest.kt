package com.example.myvlogapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseCreationTimeTest {

    @Test
    fun parsesUtcTimestamp() {
        // 2026-03-19T09:30:00.123Z
        assertEquals(1_773_912_600_123L, parseCreationTime("20260319T093000.123Z"))
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
    fun garbageIsNull() {
        assertNull(parseCreationTime(""))
        assertNull(parseCreationTime("not a date"))
    }
}
