package com.example.myvlogapp.waveform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt
import com.example.myvlogapp.LOG_TAG

/** 波形の解像度（横方向の本数）の下限。タイムライン幅に対してこれくらいあれば粗く見えない */
const val WAVEFORM_BUCKETS = 240

/**
 * 長い動画で波形の1本が受け持つ時間の目安。
 * 本数を尺に関係なく固定すると、10分の動画を数秒までズームしても表示範囲に
 * 1本しか入らず、ズームしても情報が増えない。
 */
private const val WAVEFORM_TARGET_BUCKET_MS = 100L

/** 波形の本数の上限。集計用の配列とキャッシュを際限なく太らせないため */
private const val WAVEFORM_MAX_BUCKETS = 6000

/** 尺に応じた波形の本数。短い動画は従来どおり [WAVEFORM_BUCKETS] のまま */
internal fun waveformBucketsFor(durationMs: Long): Int =
    (durationMs / WAVEFORM_TARGET_BUCKET_MS).toInt().coerceIn(WAVEFORM_BUCKETS, WAVEFORM_MAX_BUCKETS)

/** 出力バッファを待つ時間。空振りしたときだけこのぶん眠るので、CPUを回し続けずに済む */
private const val DECODE_TIMEOUT_US = 10_000L

/** 音声トラックのインデックスを探す。見つからなければ null（書き出し前に動画を調べる probeClip とも共用） */
internal fun MediaExtractor.findAudioTrackIndex(): Int? =
    (0 until trackCount).firstOrNull { index ->
        getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

/**
 * 何サンプルに1つ拾うか。
 * 全サンプルを二乗和に入れても見た目は変わらないので、間引いて処理時間を削る。
 * ステレオでも偶奇が偏らないよう奇数にしてある（4だと片チャンネルばかり拾う）。
 */
private const val SAMPLE_STRIDE = 7

/**
 * 音の波形。値は 0f〜1f に正規化済みで、要素数は [waveformBucketsFor]（尺に応じて増える）。
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
 * 選択中クリップの波形の状態。画面はこれ1つを見れば描き分けられる。
 *
 * - [isLoading] が true : まだデコード中（「波形を読み込み中…」）
 * - [waveform] が null  : 取得できなかった（「波形を取得できませんでした」）
 * - `waveform.hasAudio` が false : 音声トラックが無い（「音声なし」）
 */
data class SelectedWaveform(
    val waveform: Waveform? = null,
    val isLoading: Boolean = true
)

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
    buckets: Int = waveformBucketsFor(durationMs)
): Waveform? = withContext(Dispatchers.IO) {
    if (durationMs <= 0L) return@withContext null

    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)

        val trackIndex = extractor.findAudioTrackIndex() ?: return@withContext Waveform.Silent

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
        // 出力の形式はデコーダが知らせてくる（INFO_OUTPUT_FORMAT_CHANGED）まで、素材の値で見込んでおく
        var pcm = PcmLayout(
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            sampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE,
            channelCount = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        )
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            ensureActive()

            if (!inputDone) {
                inputDone = feedInput(codec, extractor)
            }

            val drained = drainOutput(codec, info, pcm, durationUs, sums, counts)
            pcm = drained.pcm
            outputDone = drained.done
        }

        Waveform(normalize(sums, counts), hasAudio = true)
    } catch (e: CancellationException) {
        // 取り消し（クリップを消した・選び直した）は失敗ではない。下で拾うと警告ログが出るうえ、
        // 呼び出し元へ「取り消された」ことが伝わらない
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "波形の取得に失敗しました", e)
        null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

/**
 * 空いている入力バッファへ、素材から読めるだけまとめて詰める。
 *
 * 待ち時間を0にしているのが要点で、ここで待つと「1フレームごとにタイムアウトぶん空転」が
 * 積み上がり、12秒の音声に10秒以上かかってしまう。空きが無ければ即座に呼び出し元へ返す。
 *
 * @return 素材を読み切ってEOS(終端)を送り終えたら true
 */
private fun feedInput(codec: MediaCodec, extractor: MediaExtractor): Boolean {
    while (true) {
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return false

        val buffer = codec.getInputBuffer(inputIndex)
        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
        extractor.advance()
    }
}

/**
 * 復号済みPCMの並び方。サンプルの時刻を求めるのに、1秒あたりの数とチャンネル数が要る。
 * @param encoding AudioFormat.ENCODING_PCM_*（16bit・8bit・float）
 */
private data class PcmLayout(val encoding: Int, val sampleRate: Int, val channelCount: Int)

/** 素材にもデコーダの出力にもサンプルレートが無いときの見込み（ほとんどの動画の音声がこれ） */
private const val DEFAULT_SAMPLE_RATE = 44_100

/** [drainOutput] の結果。pcmはINFO_OUTPUT_FORMAT_CHANGED時だけ更新される */
private data class DrainResult(val pcm: PcmLayout, val done: Boolean)

/**
 * 出力バッファを1回だけ待って処理する。
 * デコーダを詰めたあとなので基本すぐ返り、空振りのときだけ[DECODE_TIMEOUT_US]だけ
 * 短く眠る（busy-waitにならない）。
 */
private fun drainOutput(
    codec: MediaCodec,
    info: MediaCodec.BufferInfo,
    pcm: PcmLayout,
    durationUs: Double,
    sums: DoubleArray,
    counts: IntArray
): DrainResult {
    val outputIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
    return when {
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
            DrainResult(codec.outputFormat.pcmLayout(fallback = pcm), done = false)

        outputIndex >= 0 -> {
            if (info.size > 0) {
                codec.getOutputBuffer(outputIndex)?.let { buffer ->
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    accumulate(buffer, pcm, info.presentationTimeUs, durationUs, sums, counts)
                }
            }
            codec.releaseOutputBuffer(outputIndex, false)
            DrainResult(pcm, done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
        }

        // INFO_TRY_AGAIN_LATER と非推奨の INFO_OUTPUT_BUFFERS_CHANGED は何もしない
        else -> DrainResult(pcm, done = false)
    }
}

/**
 * 復号済みPCMの出力バッファ1つを、二乗和として区間に足し込む。
 *
 * PCM形式ごとに「サンプル数」と「i番目のサンプルを-1f〜1fへ正規化する関数」だけが違う
 * (8bit PCMは1バイト=1サンプル。開始位置が`buffer.position()`なのは、Float/Short用の
 * view bufferは位置0基準になるのに対し、生バイトはByteBuffer自体の絶対位置を使う必要があるため)。
 * 区間への振り分けは形式によらず共通なので[accumulateSamples]に任せる。
 */
private fun accumulate(
    buffer: ByteBuffer,
    pcm: PcmLayout,
    startUs: Long,
    durationUs: Double,
    sums: DoubleArray,
    counts: IntArray
) {
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    val (sampleCount, sampleAt) = when (pcm.encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val samples = buffer.asFloatBuffer()
            samples.limit() to { i: Int -> samples.get(i).toDouble() }
        }

        AudioFormat.ENCODING_PCM_8BIT -> {
            val start = buffer.position()
            val count = buffer.limit() - start
            // 8bit PCMは符号なしで中心が128
            count to { i: Int -> ((buffer.get(start + i).toInt() and 0xFF) - 128) / 128.0 }
        }

        else -> {
            val samples = buffer.asShortBuffer()
            samples.limit() to { i: Int -> samples.get(i) / 32768.0 }
        }
    }

    accumulateSamples(
        sampleCount, sampleAt, startUs, pcm.sampleRate, pcm.channelCount, durationUs, sums, counts
    )
}

/**
 * サンプルを1つずつ、その時刻が属する区間へ二乗和として足し込む。
 *
 * 出力バッファ1つ（AACなら約23ms）を先頭の時刻の区間へまとめて足していた頃は、区間がそれより短い
 * 短い動画（3秒なら240区間で1区間12.5ms）で、およそ2区間に1つが空になり、波形が櫛の歯のように
 * 途切れて見えた。サンプルごとの時刻（先頭の時刻＋何コマ目か÷サンプルレート）で振り分ける。
 *
 * @param sampleAt i番目のサンプル（-1〜1）。チャンネルは交互に並んでいる
 * @param startUs このバッファの先頭のサンプルの時刻
 */
internal fun accumulateSamples(
    sampleCount: Int,
    sampleAt: (Int) -> Double,
    startUs: Long,
    sampleRate: Int,
    channelCount: Int,
    durationUs: Double,
    sums: DoubleArray,
    counts: IntArray
) {
    val buckets = sums.size
    val usPerFrame = 1_000_000.0 / sampleRate.coerceAtLeast(1)
    val channels = channelCount.coerceAtLeast(1)
    var i = 0
    while (i < sampleCount) {
        val timeUs = startUs + (i / channels) * usPerFrame
        val bucket = (timeUs / durationUs * buckets).toInt().coerceIn(0, buckets - 1)
        val value = sampleAt(i)
        sums[bucket] += value * value
        counts[bucket]++
        i += SAMPLE_STRIDE
    }
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

/**
 * デコーダの出力の並び方。ビット形式の指定が無い端末は16bitとして扱い、
 * サンプルレートとチャンネル数が無ければそれまでの見込み（[fallback]）のままにする。
 */
private fun MediaFormat.pcmLayout(fallback: PcmLayout): PcmLayout = PcmLayout(
    encoding = intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT,
    sampleRate = intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: fallback.sampleRate,
    channelCount = intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: fallback.channelCount
)

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
