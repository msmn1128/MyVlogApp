package com.example.myvlogapp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.myvlogapp.testClip

/** 書き出しの音声の組み立て方（[AudioPlan.of]）。どのクリップの実音声を使い、どれを無音で埋めるか */
class AudioPlanTest {

    private val withAudio = ClipProbe(hasAudioTrack = true, hdrTransfer = null)
    private val withoutAudio = ClipProbe(hasAudioTrack = false, hdrTransfer = null)

    @Test
    fun usesRealAudioOnlyForUnmutedClipsThatHaveAnAudioTrack() {
        val plan = AudioPlan.of(
            clips = listOf(testClip(), testClip(isMuted = true), testClip()),
            probes = listOf(withAudio, withAudio, withoutAudio),
            includeTitle = true,
            muted = false
        )

        assertTrue(plan.hasRealAudio(0))
        assertFalse("クリップ個別のミュート", plan.hasRealAudio(1))
        assertFalse("音声トラックが無い", plan.hasRealAudio(2))
        // タイトルの効果音を入れるので、クリップの入力は1つ後ろへずれる
        assertTrue(plan.needsTitleSfxInput)
        assertEquals(1, plan.clipInputOffset)
    }

    @Test
    fun timelineMuteSilencesEveryClipAndTheTitleSound() {
        val plan = AudioPlan.of(
            clips = listOf(testClip(), testClip()),
            probes = listOf(withAudio, withAudio),
            includeTitle = true,
            muted = true
        )

        assertFalse(plan.hasRealAudio(0))
        assertFalse(plan.hasRealAudio(1))
        assertFalse(plan.needsTitleSfxInput)
        assertEquals(0, plan.clipInputOffset)
    }

    @Test
    fun noTitleMeansNoTitleSoundInput() {
        val plan = AudioPlan.of(listOf(testClip()), listOf(withAudio), includeTitle = false, muted = false)

        assertFalse(plan.needsTitleSfxInput)
        assertEquals(0, plan.clipInputOffset)
        assertTrue(plan.hasRealAudio(0))
    }

    // --- 区切りごとの書き出し（Segments.kt）での切り出し ---------------------------------

    @Test
    fun audioPlanForSegment_slicesClipsAndKeepsTheTitleSfxOnlyWhereTheTitleIs() {
        val plan = AudioPlan(needsTitleSfxInput = true, clipHasRealAudio = listOf(true, false, true, false))

        val first = plan.forSegment(0..1, includesTitle = true)
        assertTrue(first.needsTitleSfxInput)
        assertEquals(1, first.clipInputOffset)
        assertTrue(first.hasRealAudio(0))
        assertFalse(first.hasRealAudio(1))

        // 2つ目以降の区切りにはタイトルカードが無いので、効果音の-iも無い。添字は0から振り直す
        val second = plan.forSegment(2..3, includesTitle = false)
        assertFalse(second.needsTitleSfxInput)
        assertEquals(0, second.clipInputOffset)
        assertTrue(second.hasRealAudio(0))
        assertFalse(second.hasRealAudio(1))
    }
}
