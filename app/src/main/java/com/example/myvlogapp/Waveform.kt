package com.example.myvlogapp

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/** 波形の解像度（横方向の本数）。タイムライン幅に対してこれくらいあれば粗く見えない */
const val WAVEFORM_BUCKETS = 240

/** 出力バッファを待つ時間。空振りしたときだけこのぶん眠るので、CPUを回し続けずに済む */
private const val DECODE_TIMEOUT_US = 10_000L

/**
 * 何サンプルに1つ拾うか。
 * 全サンプルを二乗和に入れても見た目は変わらないので、間引いて処理時間を削る。
 * ステレオでも偶奇が偏らないよう奇数にしてある（4だと片チャンネルばかり拾う）。
 */
private const val SAMPLE_STRIDE = 7

/**
 * 音の波形。値は 0f〜1f に正規化済みで、要素数は [WAVEFORM_BUCKETS]。
 * [hasAudio] が false のときは無音（音声トラックが無い）を表す。
 */
data class Waveform(
    val amplitudes: FloatArray,
    val hasAudio: Boolean
) {
    // FloatArrayを持つdata classは equals/hashCode を自前で書かないと参照比較になり、
    // Composeが「毎回別物」と判断して無駄な再描画を繰り返してしまう。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Waveform) return false
        return hasAudio == other.hasAudio && amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int = 31 * amplitudes.contentHashCode() + hasAudio.hashCode()

    companion object {
        val Silent = Waveform(FloatArray(WAVEFORM_BUCKETS), hasAudio = false)
    }
}

/**
 * 動画の音声トラックをデコードして、区間ごとの音量（RMS）を取り出す。
 *
 * ffmpegに投げてPCMを吐かせる手もあるが、それだと数百MBの中間ファイルが要る。
 * MediaCodecなら復号結果をそのまま集計できるので、ディスクを一切使わない。
 *
 * 重い処理なので必ずバックグラウンドで呼ぶこと。呼び出し元のJobをキャンセルすれば
 * デコードも途中で止まる。
 *
 * @return 音声トラックが無い動画では [Waveform.Silent]、読めなかった場合は null
 */
suspend fun extractWaveform(
    context: Context,
    uri: Uri,
    durationMs: Long,
    buckets: Int = WAVEFORM_BUCKETS
): Waveform? = withContext(Dispatchers.IO) {
    if (durationMs <= 0L) return@withContext null

    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: return@withContext Waveform.Silent

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: return@withContext Waveform.Silent
        extractor.selectTrack(trackIndex)

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(inputFormat, null, null, 0)
            start()
        }

        val sums = DoubleArray(buckets)
        val counts = IntArray(buckets)
        val durationUs = durationMs * 1000.0
        val info = MediaCodec.BufferInfo()
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            ensureActive()

            // 入力は空きバッファがある間まとめて詰める。待ち時間を0にしているのが要点で、
            // ここで待つと「1フレームごとにタイムアウトぶん空転」が積み上がり、
            // 12秒の音声に10秒以上かかってしまう。空きが無ければ即座に出力側へ回る。
            while (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex < 0) break

                val buffer = codec.getInputBuffer(inputIndex)
                val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            // 出力はここで1回だけ待つ。デコーダを詰めたあとなので基本すぐ返り、
            // 空振りのときだけ短く眠る（busy-waitにならない）
            val outputIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    pcmEncoding = codec.outputFormat.pcmEncoding()
                }

                outputIndex >= 0 -> {
                    if (info.size > 0) {
                        val bucket = (info.presentationTimeUs / durationUs * buckets)
                            .toInt().coerceIn(0, buckets - 1)
                        codec.getOutputBuffer(outputIndex)?.let { buffer ->
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            accumulate(buffer, pcmEncoding, bucket, sums, counts)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }

                // INFO_TRY_AGAIN_LATER と非推奨の INFO_OUTPUT_BUFFERS_CHANGED は何もしない
                else -> Unit
            }
        }

        Waveform(normalize(sums, counts), hasAudio = true)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "波形の取得に失敗しました: $uri", e)
        null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

/** 復号済みPCMを二乗和として区間に足し込む */
private fun accumulate(
    buffer: ByteBuffer,
    pcmEncoding: Int,
    bucket: Int,
    sums: DoubleArray,
    counts: IntArray
) {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    var sum = 0.0
    var taken = 0

    when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val samples = buffer.asFloatBuffer()
            var i = 0
            while (i < samples.limit()) {
                val value = samples.get(i).toDouble()
                sum += value * value
                taken++
                i += SAMPLE_STRIDE
            }
        }

        AudioFormat.ENCODING_PCM_8BIT -> {
            var i = buffer.position()
            while (i < buffer.limit()) {
                // 8bit PCMは符号なしで中心が128
                val value = ((buffer.get(i).toInt() and 0xFF) - 128) / 128.0
                sum += value * value
                taken++
                i += SAMPLE_STRIDE
            }
        }

        else -> {
            val samples = buffer.asShortBuffer()
            var i = 0
            while (i < samples.limit()) {
                val value = samples.get(i) / 32768.0
                sum += value * value
                taken++
                i += SAMPLE_STRIDE
            }
        }
    }

    sums[bucket] += sum
    counts[bucket] += taken
}

/**
 * RMSへ直してから最大値で割る。
 *
 * 最後に0.6乗しているのは、生のRMSだと会話くらいの音量が全体の1割ほどの高さにしかならず、
 * 波形がほぼ平らに見えてしまうため。音量の大小関係は保ったまま小さい音を持ち上げている。
 */
private fun normalize(sums: DoubleArray, counts: IntArray): FloatArray {
    val rms = DoubleArray(sums.size) { i ->
        if (counts[i] == 0) 0.0 else sqrt(sums[i] / counts[i])
    }
    val peak = rms.max()
    if (peak <= 1e-5) return FloatArray(sums.size)
    return FloatArray(sums.size) { i -> (rms[i] / peak).pow(0.6).toFloat().coerceIn(0f, 1f) }
}

/** 出力PCMのビット形式。指定が無い端末は16bitとして扱う */
private fun MediaFormat.pcmEncoding(): Int =
    runCatching { getInteger(MediaFormat.KEY_PCM_ENCODING) }
        .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
