package com.example.myvlogapp.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.waveform.findAudioTrackIndex

// =====================================================================================
// 書き出しの前に、各クリップの動画の中身を調べる。
//
// 音声トラックがあるか（無ければ無音を作って埋める。AudioPlan）と、HDRで撮られたか
// （SDRへ変換する。Hdr.kt）の2つ。以前は別々に調べていて、1本の動画を2回開いていた。
// 1回開けば両方読めるので、ここでまとめて読む（100本だと開く回数が200回→100回）。
// =====================================================================================

/**
 * @param hasAudioTrack 音声トラックがあるか
 * @param hdrTransfer HDRならその伝達特性。SDRはnull
 */
internal data class ClipProbe(val hasAudioTrack: Boolean, val hdrTransfer: HdrTransfer?)

/**
 * 動画を1回開いて[ClipProbe]を読む。重いI/Oなので、呼び出し側で並列に（本数を絞って）呼ぶ。
 *
 * 読めなかったときは「音声なし・SDR」として扱い、書き出し自体は止めない。読めない動画は、
 * 書き出し前の確認（VlogExporter.requireAllReadable）で先に断っているので、ここで失敗するのは
 * 開けても中身を解釈できなかった場合だけ。音声なし＝無音を作って埋める、SDR＝変換しない、
 * のどちらも、その動画を書き出せなくする扱いではない。
 */
internal fun probeClip(context: Context, uri: Uri): ClipProbe {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        val video = (0 until extractor.trackCount)
            .map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        ClipProbe(
            hasAudioTrack = extractor.findAudioTrackIndex() != null,
            hdrTransfer = video?.let(::hdrTransferOf)
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "動画の中身を調べられませんでした（音声なし・SDRとして扱います）: $uri", e)
        ClipProbe(hasAudioTrack = false, hdrTransfer = null)
    } finally {
        extractor.release()
    }
}
