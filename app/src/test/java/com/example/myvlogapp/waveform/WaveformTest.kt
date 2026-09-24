package com.example.myvlogapp.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 復号したPCMを波形の区間へ振り分ける計算（Waveform.ktの[accumulateSamples]） */
class WaveformSamplesTest {

    @Test
    fun oneBufferLongerThanABucketFillsEveryBucketItCovers() {
        // 1秒の動画を10区間（1区間100ms）に分ける。0秒から始まる1秒ぶん（1kHz・モノラル）の
        // バッファが1つだけ届いても、全区間に音が入る。以前はバッファの先頭の区間にまとめて
        // 足していたので、区間0以外が空（波形が点）になっていた
        val sums = DoubleArray(10)
        val counts = IntArray(10)

        accumulateSamples(
            sampleCount = 1000, sampleAt = { 1.0 }, startUs = 0L,
            sampleRate = 1000, channelCount = 1, durationUs = 1_000_000.0, sums = sums, counts = counts
        )

        assertTrue(counts.joinToString(), counts.all { it > 0 })
    }

    @Test
    fun samplesLandInTheBucketOfTheirOwnTime() {
        // 500msから始まる100msぶんのバッファは、区間5（500〜600ms）にだけ入る
        val sums = DoubleArray(10)
        val counts = IntArray(10)

        accumulateSamples(
            sampleCount = 100, sampleAt = { 0.5 }, startUs = 500_000L,
            sampleRate = 1000, channelCount = 1, durationUs = 1_000_000.0, sums = sums, counts = counts
        )

        assertEquals(listOf(5), counts.indices.filter { counts[it] > 0 })
    }

    @Test
    fun channelsShareTheTimeOfTheirFrame() {
        // ステレオは左右が交互に並ぶ。200サンプル＝100コマ＝100msなので、区間0にだけ入る
        // （チャンネル数で割らないと200msぶんと数えて、区間1まではみ出す）
        val sums = DoubleArray(10)
        val counts = IntArray(10)

        accumulateSamples(
            sampleCount = 200, sampleAt = { 0.5 }, startUs = 0L,
            sampleRate = 1000, channelCount = 2, durationUs = 1_000_000.0, sums = sums, counts = counts
        )

        assertEquals(listOf(0), counts.indices.filter { counts[it] > 0 })
    }

    @Test
    fun samplesPastTheEndStayInTheLastBucket() {
        // 音声が映像より少し長い素材でも、配列の外へは書かない
        val sums = DoubleArray(10)
        val counts = IntArray(10)

        accumulateSamples(
            sampleCount = 100, sampleAt = { 0.5 }, startUs = 1_050_000L,
            sampleRate = 1000, channelCount = 1, durationUs = 1_000_000.0, sums = sums, counts = counts
        )

        assertEquals(listOf(9), counts.indices.filter { counts[it] > 0 })
    }
}

/** 波形の本数（[waveformBucketsFor]）。長い動画ほど増やし、上限で止める */
class WaveformBucketsTest {

    @Test
    fun shortClipsKeepTheBaseResolution() {
        assertEquals(WAVEFORM_BUCKETS, waveformBucketsFor(10_000L))
        assertEquals(WAVEFORM_BUCKETS, waveformBucketsFor(24_000L))
    }

    @Test
    fun longClipsGetOneBucketPer100ms() {
        assertEquals(600, waveformBucketsFor(60_000L))
    }

    @Test
    fun veryLongClipsAreCapped() {
        assertEquals(6_000, waveformBucketsFor(600_000L))
        assertEquals(6_000, waveformBucketsFor(3_600_000L))
    }
}
