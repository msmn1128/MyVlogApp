package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

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
    val trimmedDurationMs: Long get() = trimmedDurationMs(startMs, endMs)
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

    companion object {
        /**
         * [toJson] で書き出したJSONから復元する。
         *
         * uriの読み取り可否チェックやidの発行は呼び出し元（ClipStore）の責務なので、
         * ここでは純粋にJSON→VlogClipの変換だけを行う。
         */
        fun fromJson(json: JSONObject, id: Long): VlogClip = VlogClip(
            id = id,
            uri = Uri.parse(json.getString("uri")),
            timeText = json.getString("timeText"),
            dateText = json.getString("dateText"),
            durationMs = json.getLong("durationMs"),
            width = json.getInt("width"),
            height = json.getInt("height"),
            texts = json.readTextSegments(),
            startMs = json.getLong("startMs"),
            endMs = json.getLong("endMs")
        )
    }
}

/**
 * [VlogClip.trimmedDurationMs] と同じ計算。
 * ClipStoreの一覧表示のように、VlogClipを組み立てず生のJSONだけから
 * 尺を求めたい場面でも同じロジックを使い回すため独立させてある。
 */
fun trimmedDurationMs(startMs: Long, endMs: Long): Long = (endMs - startMs).coerceAtLeast(0L)

/** [VlogClip] をJSONへ。ClipStoreの自動保存・一時保存の両方で同じ形を使う */
fun VlogClip.toJson(): JSONObject = JSONObject().apply {
    put("uri", uri.toString())
    put("timeText", timeText)
    put("dateText", dateText)
    put("durationMs", durationMs)
    put("width", width)
    put("height", height)
    put("texts", JSONArray().apply {
        texts.forEach { segment ->
            put(
                JSONObject()
                    .put("startMs", segment.startMs)
                    .put("text", segment.text)
            )
        }
    })
    put("startMs", startMs)
    put("endMs", endMs)
}

/**
 * ひとことの区間を読む。
 *
 * 区間を持たせる前のバージョンで保存された分は "userText" しか無いので、
 * その1件を先頭区間として読み直す（更新しても前回の続きが消えない）。
 */
private fun JSONObject.readTextSegments(): List<TextSegment> {
    val array = optJSONArray("texts")
        ?: return listOf(TextSegment(0L, optString("userText", DEFAULT_HITOKOTO)))

    // 昇順に直してから返す。区間の判定（textIndexAt / visibleTextSpans）は
    // 「前から順に並んでいる」前提で書かれているので、並びが崩れていると
    // ひとことが拾えない区間ができ、書き出しから文字が消える。
    val segments = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        TextSegment(
            startMs = item.optLong("startMs"),
            text = item.optString("text", DEFAULT_HITOKOTO)
        )
    }.sortedBy { it.startMs }

    if (segments.isEmpty()) return listOf(TextSegment())

    // 先頭が0から始まらないと textAt が拾えない区間ができてしまう。
    // 以前はこの場合に全区間を白紙(listOf(TextSegment()))へ丸ごと差し替えていたが、
    // それだと1件目のstartMsが壊れているだけで残り全部のひとこと文言まで消えてしまう。
    // 先頭の位置だけ0へ直し、文言はそのまま残す。
    val first = segments.first()
    return if (first.startMs == 0L) segments
    else listOf(first.copy(startMs = 0L)) + segments.drop(1)
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
