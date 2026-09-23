package com.example.myvlogapp.export

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.MAX_CLIPS

// =====================================================================================
// FFmpegの実行と中止。
//
// VlogExporter.kt から切り出したもの。実行中のセッションを控えておくのはここだけで、
// 書き出しの手順（VlogExporter.export）も中止の入口（VlogExporter.cancel）も
// この2つの関数を呼ぶだけにしてある。
// =====================================================================================

/**
 * ログに残すFFmpegのコマンド・フィルタグラフの最大文字数。
 * 本数が多い（[MAX_CLIPS]本）と1行が数百KBになって、Logcatを埋めてしまう。
 */
internal const val COMMAND_LOG_MAX_CHARS = 2000

private const val LOG_CHUNK_SIZE = 3000
private const val ERROR_SNIPPET_MAX_CHARS = 400
private const val ERROR_HIT_LINE_LIMIT = 3

/** いま走らせている書き出しセッション。[cancelRunningFFmpeg]が狙い撃ちするために控えておく */
@Volatile
private var runningSessionId: Long? = null

/**
 * 「中止」が要求されたか。
 *
 * セッションIDは`executeWithArgumentsAsync`が返ってきて初めて分かるため、その直前に
 * 中止されると[cancelRunningFFmpeg]は止める相手を見つけられない。コルーチンは止まるが、
 * ネイティブのエンコードだけが走り続けてしまう（CPUと電池を使い続ける）。
 * 要求をここに残し、IDが分かった時点で取りこぼしを拾う。
 */
@Volatile
private var cancelRequested = false

/** 前回の書き出しの中止要求を引きずらないよう、始める前に必ず下ろす */
internal fun resetCancelRequest() {
    cancelRequested = false
}

/**
 * 実行中のFFmpeg処理を中断する。
 *
 * 引数なしの`FFmpegKit.cancel()`は**実行中の全セッション**を止めるため、
 * 起動直後の機能判定（FFmpegCapabilities.ktの`-encoders`/`-filters`）が同時に走っていると
 * それも巻き込んで空文字を返させ、エンコーダの判定結果が変わってしまう。
 * 書き出し本体のセッションだけを狙って止める。
 */
internal fun cancelRunningFFmpeg() {
    // セッションが始まる前（機能判定や下ごしらえの最中）に押された場合、この時点では
    // 止める相手がいない。要求だけ覚えておき、セッションが始まった直後に
    // [runFFmpegWithProgress]が拾って止める。
    cancelRequested = true
    runningSessionId?.let { FFmpegKit.cancel(it) }
}

/**
 * FFmpegを実行し、経過時間から進捗率（%）を算出してonProgressに渡す。
 *
 * statisticsコールバックはFFmpegKit側の別スレッドから呼ばれる。[onProgress]は
 * suspendではない素の関数にしてあるので、そのスレッドからそのまま呼べる
 * （以前はsuspend関数をrunBlockingで橋渡ししており、呼び出し元のディスパッチャが
 * 混んでいるとFFmpeg側のコールバックスレッドを待たせていた）。
 * パーセント値が変わったときだけ呼ぶことで、呼び出し頻度（1秒間に何度も飛んでくる）に
 * よる無駄な更新を減らす。
 *
 * セッションは[runningSessionId]に控えておき、[cancelRunningFFmpeg]がこのセッションだけを
 * 狙って止められるようにする。
 */
internal suspend fun runFFmpegWithProgress(
    args: Array<String>,
    totalDurationMs: Long,
    onProgress: (message: String, progress: Float?) -> Unit
) {
    Log.d(LOG_TAG, "ffmpeg ${args.joinToString(" ").take(COMMAND_LOG_MAX_CHARS)}")
    val completion = CompletableDeferred<FFmpegSession>()
    var lastPercent = -1

    val started = FFmpegKit.executeWithArgumentsAsync(
        args,
        { session -> completion.complete(session) },
        { /* ログはセッション完了後にまとめて参照するのでここでは何もしない */ },
        { statistics ->
            if (totalDurationMs > 0) {
                val ratio = (statistics.time / totalDurationMs.toDouble()).coerceIn(0.0, 1.0)
                val percent = (ratio * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress("書き出し中... $percent%", ratio.toFloat())
                }
            }
        }
    )
    runningSessionId = started.sessionId
    // セッションが始まる前に「中止」が押されていた場合の取りこぼしを、ここで拾う
    if (cancelRequested) FFmpegKit.cancel(started.sessionId)

    val session = try {
        completion.await()
    } finally {
        // キャンセルで抜ける場合もここを通る。呼び出し元（サービス）がcancel()を
        // 呼んでいれば既に止まっているが、それを呼ばない経路から打ち切られると、
        // コルーチンだけが抜けてネイティブのエンコードは走り続ける（CPUと電池を
        // 使い続け、作業ファイルの掃除も走らない）。中止の経路が増えても取りこぼさない
        // よう、自分が起こしたセッションはここで自分で止めてから控えを消す。
        if (!completion.isCompleted) FFmpegKit.cancel(started.sessionId)
        runningSessionId = null
    }
    when {
        ReturnCode.isSuccess(session.returnCode) -> Unit
        // 中止はキャンセルとして投げる。失敗（VlogExportException）として投げると、
        // ネイティブ側の完了がコルーチンのキャンセルより先に届いたとき、呼び出し元（サービス）が
        // 失敗の分岐へ入り、中止したのに「書き出しに失敗しました」と通知していた
        ReturnCode.isCancel(session.returnCode) -> throw CancellationException("書き出しを中止しました")
        else -> {
            val log = session.allLogsAsString.orEmpty()
            logFfmpegOutput("書き出しに失敗しました", "${session.returnCode}", log)
            throw VlogExportException("書き出しに失敗しました\n${extractReason(log)}")
        }
    }
}

/**
 * Logcatは1行あたりの長さに上限があり、FFmpegの出力は途中で切れてしまう。
 * 分割して全文を出し、目印で挟んで探しやすくする。
 */
private fun logFfmpegOutput(errorMessage: String, code: String, log: String) {
    Log.e(LOG_TAG, "$errorMessage / code=$code")
    Log.e(LOG_TAG, "--- FFmpeg出力ここから ---")
    log.chunked(LOG_CHUNK_SIZE).forEach { Log.e(LOG_TAG, it) }
    Log.e(LOG_TAG, "--- FFmpeg出力ここまで ---")
}

/** FFmpegの出力からエラーらしい行だけ拾ってToastに出す */
private fun extractReason(log: String): String {
    val keywords = listOf(
        "error", "invalid", "no such", "unable", "failed", "permission denied", "conversion failed"
    )
    val hits = log.lineSequence()
        .map { it.trim() }
        .filter { line -> line.isNotEmpty() && keywords.any { line.contains(it, ignoreCase = true) } }
        .distinct()
        .toList()
    return if (hits.isEmpty()) log.takeLast(ERROR_SNIPPET_MAX_CHARS).trim()
    else hits.takeLast(ERROR_HIT_LINE_LIMIT).joinToString("\n").take(ERROR_SNIPPET_MAX_CHARS)
}
