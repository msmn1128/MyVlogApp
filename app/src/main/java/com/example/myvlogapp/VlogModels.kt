package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.net.Uri

// =====================================================================================
// データモデル
//
// 定数は VlogConstants.kt、Android I/O依存のメタデータ取得は VideoMetadataReader.kt、
// 表示整形は Formatters.kt にそれぞれ分離してある。このファイルは純粋データのみを持つ。
// =====================================================================================

/**
 * タイムライン上の1クリップ。
 * Composeの再コンポーズを正しく発火させるため、全プロパティを val（不変）にして
 * 変更時は copy() で新しいインスタンスを作る。
 */
data class VlogClip(
    val id: Long,               // LazyRowのkey用の安定ID
    val uri: Uri,
    val timeText: String,       // 撮影時刻 "HH:mm"（24時間表記）
    val dateText: String,       // 撮影日 "yyyy/MM/dd"
    val durationMs: Long,
    val width: Int,             // 回転補正済みの実表示幅
    val height: Int,            // 回転補正済みの実表示高さ
    val texts: List<TextSegment> = listOf(TextSegment()),
    val startMs: Long = 0L,
    val endMs: Long = 0L
) {
    val trimmedDurationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val isValid: Boolean get() = durationMs > 0 && endMs > startMs

    /** 区切り位置（2番目以降の区間の頭）。波形に紫のラインを引くのに使う */
    val splitPoints: List<Long> get() = texts.drop(1).map { it.startMs }

    /** その位置に出るひとことが何番目の区間か。番号表示と編集対象の特定に使う */
    fun textIndexAt(positionMs: Long): Int =
        texts.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /** その位置に出るひとこと */
    fun textAt(positionMs: Long): String = texts[textIndexAt(positionMs)].text

    /**
     * 区切りのうち [positionMs] のすぐ近くにあるもの。無ければ null。
     *
     * 許容幅を尺に比例させているのは、長い動画ほど波形1px当たりの時間が長く、
     * 固定値だと「線の上に置いたつもり」でも外れてしまうため。
     */
    fun splitPointNear(positionMs: Long): Long? = splitPoints
        .minByOrNull { kotlin.math.abs(it - positionMs) }
        ?.takeIf { kotlin.math.abs(it - positionMs) <= splitToleranceMs() }

    private fun splitToleranceMs(): Long = (durationMs / 40).coerceIn(200L, 1500L)

    /**
     * トリミング範囲に実際に映るひとことを、区間ごとに切り出す。
     * 書き出しのdrawtextはこの区間ぶんだけ表示すればよい。
     *
     * トリミングで頭を落としたとき、その手前の区間は尺が無くなるので落とす。
     * ただし「トリム開始時点で出ている文字」は残す（indexOfLastで拾われる区間がそれ）。
     */
    fun visibleTextSpans(): List<TextSpan> {
        val first = textIndexAt(startMs)
        return texts.mapIndexedNotNull { index, segment ->
            if (index < first) return@mapIndexedNotNull null
            val spanStart = maxOf(segment.startMs, startMs)
            val spanEnd = texts.getOrNull(index + 1)?.startMs?.coerceAtMost(endMs) ?: endMs
            if (spanEnd <= spanStart) null
            else TextSpan(number = index + 1, startMs = spanStart, endMs = spanEnd, text = segment.text)
        }
    }
}

/**
 * クリップの途中でひとことを差し替えるための1区間。
 *
 * 動画そのものは分割しない。文字だけを時間で切り替えるので、
 * カット点でつなぎ目が生まれず、書き出しも1本のままで済む。
 *
 * [startMs] は動画内の絶対位置（トリミング位置と同じ基準）。先頭は必ず 0。
 */
data class TextSegment(
    val startMs: Long = 0L,
    val text: String = DEFAULT_HITOKOTO
)

/** [VlogClip.visibleTextSpans] の結果。number は画面に出す 1,2,3… の通し番号 */
data class TextSpan(
    val number: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class VideoMeta(
    val timeText: String,
    val dateText: String,
    val durationMs: Long,
    val width: Int,
    val height: Int
)

/** 書き出しの進行状態。UIはこれを見るだけでよい */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val message: String) : ExportState
}

/** Toastなど「1回だけ通知したい」イベント */
sealed interface VlogEvent {
    data class Message(val text: String) : VlogEvent
}
