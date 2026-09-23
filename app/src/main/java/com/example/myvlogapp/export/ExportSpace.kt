package com.example.myvlogapp.export

import java.util.Locale

// =====================================================================================
// 書き出しに要る空き容量。
//
// 書き出しは、作業フォルダ（cacheDir）に動画を作ってからギャラリーへコピーするので、
// 出来上がりの大きさの2倍が一時的に要る。本数が多くて区切りごとに書き出すとき
// （Segments.kt）は、さらに区切りの中間ファイル（映像＋無圧縮の音声）が加わって約3倍になる。
// 容量が足りないと、FFmpegの英語のエラー（No space left on device）のまま途中で失敗していた。
// 書き出しの前に見積もって断り、それでも途中で尽きたときは日本語で知らせる。
// =====================================================================================

/** 見積もりに足す余裕。フィルタグラフや行ごとのテキストなどの細かいファイルと、見積もりの誤差のぶん */
private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024

/** 区切りの中間ファイルの音声（無圧縮のPCM、16ビット）のビットレート */
private const val PCM_BITRATE_BPS = AUDIO_SAMPLE_RATE.toLong() * AUDIO_CHANNELS * 16

/** 容量不足のときに見せる文言の結び。どうすればよいかまで伝える */
private const val NO_SPACE_ADVICE = "不要な動画やアプリを削除するか、クリップを減らしてから書き出してください"

/**
 * 出来上がりの動画の大きさの見積もり（バイト）。映像はハードウェアエンコーダに指定している
 * ビットレート（上限の目安）で見積もるので、簡単な映像では実際はこれより小さくなる。
 */
internal fun estimatedOutputBytes(durationMs: Long): Long =
    durationMs * (MEDIACODEC_BITRATE_BPS + AUDIO_BITRATE_BPS) / 8 / 1000

/**
 * 書き出しに要る空き容量（バイト）。
 * 1回で書き出すとき: 作業フォルダの動画＋ギャラリーへのコピー。
 * 区切りごとのとき: それに区切りの中間ファイル（映像＋無圧縮の音声）が加わる。
 */
internal fun requiredFreeBytes(durationMs: Long, segmented: Boolean): Long {
    val output = estimatedOutputBytes(durationMs)
    val intermediates =
        if (segmented) durationMs * (MEDIACODEC_BITRATE_BPS + PCM_BITRATE_BPS) / 8 / 1000 else 0L
    return output * 2 + intermediates + SPACE_MARGIN_BYTES
}

/**
 * 書き出す前に空き容量が足りないと分かったときの文言。
 * [availableBytes] は、システムが動作のために残しておく分を除いた「アプリが使える空き」なので、
 * 設定アプリに出る空きより小さい（0になることもある）。見比べて混乱しないよう「使える空き」と書く
 */
internal fun notEnoughSpaceMessage(requiredBytes: Long, availableBytes: Long): String =
    "端末の空き容量が足りません（書き出しに約${sizeText(requiredBytes)}必要ですが、" +
            "使える空きは${sizeText(availableBytes)}です）。$NO_SPACE_ADVICE"

/** 書き出しの途中で容量が尽きたときの文言 */
internal const val RAN_OUT_OF_SPACE_MESSAGE =
    "書き出しの途中で端末の空き容量が足りなくなりました。$NO_SPACE_ADVICE"

/** FFmpegの出力や例外の文言が、容量不足によるものか */
internal fun isNoSpaceError(text: String?): Boolean =
    text != null && (text.contains("No space left", ignoreCase = true) || text.contains("ENOSPC"))

/**
 * 「1.4GB」「245MB」のような大きさの表記。1GB未満をGBで出すと「0.1GB」「0.2GB」のように
 * 差が読み取りにくいので、MBで出す。数字の字形が変わらないようLocale.USで組み立てる
 */
private fun sizeText(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024)
    return if (mb >= 1024) String.format(Locale.US, "%.1fGB", mb / 1024)
    else String.format(Locale.US, "%.0fMB", mb)
}
