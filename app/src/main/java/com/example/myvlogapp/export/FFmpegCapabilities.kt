package com.example.myvlogapp.export

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.myvlogapp.LOG_TAG

// =====================================================================================
// 出力フォーマットと、FFmpegの機能判定。
//
// VlogExporter.kt から切り出したもの。ffmpeg-kitはビルド種類（min / full / full-gpl /
// フォーク各種）によって使えるエンコーダやフィルタが異なる。依存を差し替えるたびに
// コードを直さずに済むよう、実際に何が使えるかを起動後1回だけ調べて使い分ける。
// =====================================================================================

// --- 音声フォーマット -----------------------------------------------------------------
// 無音クリップの補完(anullsrc、FilterGraph.kt)と実際のエンコード出力(-ar/-ac/-b:a)の
// 両方でこの値を使う。片方だけ変えると無音クリップだけサンプルレートが食い違うため、
// 必ずここを経由する。

internal const val AUDIO_SAMPLE_RATE = 44100
internal const val AUDIO_CHANNEL_LAYOUT = "stereo" // anullsrcの cl= 用
internal const val AUDIO_CHANNELS = 2              // -ac 用
// ビットレートは数値で持つ。書き出し前の空き容量の見積もり（ExportSpace.kt）でも同じ値を使い、
// 実際の設定と見積もりが食い違わないようにするため
internal const val AUDIO_BITRATE_BPS = 128_000L
internal const val AUDIO_BITRATE = "$AUDIO_BITRATE_BPS"

/** h264_mediacodec（ハードウェアエンコーダ）使用時のビットレート */
internal const val MEDIACODEC_BITRATE_BPS = 12_000_000L
private const val MEDIACODEC_BITRATE = "$MEDIACODEC_BITRATE_BPS"

private data class Capabilities(
    val videoEncoder: String,
    val extraVideoArgs: List<String>,
    val hasDrawtext: Boolean
)

/**
 * 判定済みの結果。判定そのものに失敗した回（出力が空）は覚えない。
 *
 * `by lazy`で持っていた頃は、一度失敗すると「drawtextが無い」という結果がプロセスの
 * 終わりまで残り、アプリを再起動するまで書き出しがすべて失敗していた。
 */
@Volatile
private var probedCapabilities: Capabilities? = null

private val capabilities: Capabilities
    get() = probedCapabilities ?: synchronized(Capabilities::class) {
        probedCapabilities ?: probeCapabilities()
    }

private fun probeCapabilities(): Capabilities {
    val encoders = runCatching {
        FFmpegKit.execute("-hide_banner -encoders").allLogsAsString.orEmpty()
    }.getOrDefault("")
    val filters = runCatching {
        FFmpegKit.execute("-hide_banner -filters").allLogsAsString.orEmpty()
    }.getOrDefault("")

    val hasDrawtext = filters.contains("drawtext")
    val caps = when {
        // GPL版に含まれるソフトウェアH.264エンコーダ。品質・互換性ともに最良。
        encoders.contains("libx264") -> Capabilities("libx264", emptyList(), hasDrawtext)

        // 端末のハードウェアエンコーダ。libx264が無いビルドでの代替。
        // ビットレート指定が無いと極端に低品質になるため明示する。
        encoders.contains("h264_mediacodec") ->
            Capabilities("h264_mediacodec", listOf("-b:v", MEDIACODEC_BITRATE), hasDrawtext)

        // 最後の手段。mp4に入るが圧縮効率は落ちる。
        else -> Capabilities("mpeg4", listOf("-q:v", "3"), hasDrawtext)
    }

    Log.i(
        LOG_TAG,
        "FFmpeg機能判定: encoder=${caps.videoEncoder} drawtext=${caps.hasDrawtext}"
    )
    if (encoders.isNotEmpty() && filters.isNotEmpty()) probedCapabilities = caps
    return caps
}

/**
 * 映像エンコード用の共通引数。
 *
 * -r（フレームレート強制）を付けないのは、結合後の連続した映像に対して
 * 呼び出し元がすでに`fps`フィルタでCFR化しているため。クリップ個別に-rを
 * 掛けていた頃は、素材の実フレームレートとの差分の帳尻合わせがクリップ末尾
 * （＝つなぎ目）に集中してしまい、継ぎ目で一瞬止まって見える原因になっていた。
 */
internal fun videoEncodeArgs(): Array<String> = capabilities.let { caps ->
    arrayOf(
        "-c:v", caps.videoEncoder,
        *caps.extraVideoArgs.toTypedArray(),
        "-pix_fmt", "yuv420p",
        // 出力はSDRのBT.709（HDRの素材もここまでに変換してある）。付けないと再生する側が
        // 色の変換式を推測することになり、BT.601と取られると赤などの色がずれて見える
        "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"
    )
}

/** 仕上がりの音声（AAC）の引数 */
internal fun aacAudioArgs(): Array<String> = arrayOf(
    "-c:a", "aac", "-ar", "$AUDIO_SAMPLE_RATE", "-ac", "$AUDIO_CHANNELS", "-b:a", AUDIO_BITRATE
)

/**
 * 区切りごとの書き出し（Segments.kt）の中間ファイルの音声（無圧縮のPCM）の引数。
 * AACにしないのは、つなぐと区切りごとにエンコーダの遅延ぶん音がずれていくため
 */
internal fun pcmAudioArgs(): Array<String> = arrayOf(
    "-c:a", "pcm_s16le", "-ar", "$AUDIO_SAMPLE_RATE", "-ac", "$AUDIO_CHANNELS"
)

internal fun requireDrawtext() {
    if (!capabilities.hasDrawtext) {
        throw VlogExportException(
            "このFFmpegビルドにはdrawtextフィルタが含まれておらず、文字を焼き込めません。" +
                    "freetypeを含むビルド（full / full-gpl）に差し替えてください。"
        )
    }
}
