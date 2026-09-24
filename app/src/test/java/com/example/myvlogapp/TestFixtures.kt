package com.example.myvlogapp

import android.net.Uri
import io.mockk.mockk

// テスト全体で使う部品。以前はVlogClipTest.ktの中にあり、他のテストがそこへ依存していた

/** VlogClip はUriを持つが、テスト対象の計算はUriに触れないのでモックで足りる */
internal fun testClip(
    id: Long = 1L,
    durationMs: Long = 10_000L,
    startMs: Long = 0L,
    endMs: Long = durationMs,
    texts: List<TextSegment> = listOf(TextSegment()),
    shotAtMillis: Long = 0L,
    dateText: String = "2026/01/01",
    timeText: String = "00:00",
    isMuted: Boolean = false,
    shotAtReliable: Boolean = true
) = VlogClip(
    id = id,
    uri = mockk<Uri>(relaxed = true),
    timeText = timeText,
    dateText = dateText,
    durationMs = durationMs,
    texts = texts,
    startMs = startMs,
    endMs = endMs,
    shotAtMillis = shotAtMillis,
    isMuted = isMuted,
    shotAtReliable = shotAtReliable
)
