package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

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
    val endMs: Long = 0L,
    val shotAtMillis: Long = 0L // 撮影/作成日時（並び替えの基準）。0は未取得・旧データ
) {
    val trimmedDurationMs: Long get() = trimmedDurationMs(startMs, endMs)
    val isValid: Boolean get() = durationMs > 0 && endMs > startMs

    /**
     * 撮影日時順の並び替えに使うキー。
     * [shotAtMillis] が無い旧データは [dateText]/[timeText] から逆算し、
     * それも壊れていれば最後尾へ送る（並びを壊さないため）。
     */
    val sortKeyMs: Long get() = if (shotAtMillis > 0L) shotAtMillis else parseShotAtText(dateText, timeText)

    /** 区切り位置（2番目以降の区間の頭）。波形に紫のラインを引くのに使う */
    val splitPoints: List<Long> get() = texts.drop(1).map { it.startMs }

    /** その位置に出るひとことが何番目の区間か。番号表示と編集対象の特定に使う */
    fun textIndexAt(positionMs: Long): Int =
        texts.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /**
     * その位置に出るひとこと。
     * textsが空になる経路は現状無い（生成・復元・編集のどれも必ず1件以上残す）が、
     * ここで添字アクセスが例外を投げると画面全体が落ちるため、防御的にgetOrNullで読む。
     */
    fun textAt(positionMs: Long): String =
        texts.getOrNull(textIndexAt(positionMs))?.text ?: DEFAULT_HITOKOTO

    /**
     * 区切りのうち [positionMs] のすぐ近くにあるもの。無ければ null。
     *
     * 許容幅を尺に比例させているのは、長い動画ほど波形1px当たりの時間が長く、
     * 固定値だと「線の上に置いたつもり」でも外れてしまうため。
     */
    fun splitPointNear(positionMs: Long): Long? = splitPoints
        .minByOrNull { abs(it - positionMs) }
        ?.takeIf { abs(it - positionMs) <= splitToleranceMs() }

    private fun splitToleranceMs(): Long =
        (durationMs / SPLIT_TOLERANCE_DIVISOR).coerceIn(SPLIT_TOLERANCE_MIN_MS, SPLIT_TOLERANCE_MAX_MS)

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
            uri = Uri.parse(json.getString(VlogClipKeys.URI)),
            timeText = json.getString(VlogClipKeys.TIME_TEXT),
            dateText = json.getString(VlogClipKeys.DATE_TEXT),
            durationMs = json.getLong(VlogClipKeys.DURATION_MS),
            width = json.getInt(VlogClipKeys.WIDTH),
            height = json.getInt(VlogClipKeys.HEIGHT),
            texts = json.readTextSegments(),
            startMs = json.getLong(VlogClipKeys.START_MS),
            endMs = json.getLong(VlogClipKeys.END_MS),
            // 並び替え機能を追加する前の保存データにはキー自体が無いので optLong で0にフォールバック
            shotAtMillis = json.optLong(VlogClipKeys.SHOT_AT_MILLIS)
        )
    }
}

/**
 * [VlogClip.trimmedDurationMs] と同じ計算。
 * ClipStoreの一覧表示のように、VlogClipを組み立てず生のJSONだけから
 * 尺を求めたい場面でも同じロジックを使い回すため独立させてある。
 */
fun trimmedDurationMs(startMs: Long, endMs: Long): Long = (endMs - startMs).coerceAtLeast(0L)

/**
 * [VlogClip]のJSON保存に使うキー名。
 *
 * ClipStore側もいくつかのキー（[START_MS]/[END_MS]/[TEXTS]）をVlogClipを
 * 組み立てずに直接読む箇所（一時保存一覧の尺集計）があるため、ここに集約して
 * 両ファイルでキー名がズレて片方だけ壊れる事故を防ぐ。
 */
object VlogClipKeys {
    const val URI = "uri"
    const val TIME_TEXT = "timeText"
    const val DATE_TEXT = "dateText"
    const val DURATION_MS = "durationMs"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val TEXTS = "texts"
    const val TEXT = "text"
    const val START_MS = "startMs"
    const val END_MS = "endMs"
    const val SHOT_AT_MILLIS = "shotAtMillis"

    /** 区間(texts)を持たせる前の旧バージョンで使われていたキー。読み込み専用の後方互換 */
    const val LEGACY_USER_TEXT = "userText"
}

/** [VlogClip] をJSONへ。ClipStoreの自動保存・一時保存の両方で同じ形を使う */
fun VlogClip.toJson(): JSONObject = JSONObject().apply {
    put(VlogClipKeys.URI, uri.toString())
    put(VlogClipKeys.TIME_TEXT, timeText)
    put(VlogClipKeys.DATE_TEXT, dateText)
    put(VlogClipKeys.DURATION_MS, durationMs)
    put(VlogClipKeys.WIDTH, width)
    put(VlogClipKeys.HEIGHT, height)
    put(VlogClipKeys.TEXTS, JSONArray().apply {
        texts.forEach { segment ->
            put(
                JSONObject()
                    .put(VlogClipKeys.START_MS, segment.startMs)
                    .put(VlogClipKeys.TEXT, segment.text)
            )
        }
    })
    put(VlogClipKeys.START_MS, startMs)
    put(VlogClipKeys.END_MS, endMs)
    put(VlogClipKeys.SHOT_AT_MILLIS, shotAtMillis)
}

/**
 * ひとことの区間を読む。
 *
 * 区間を持たせる前のバージョンで保存された分は [VlogClipKeys.LEGACY_USER_TEXT] しか
 * 無いので、その1件を先頭区間として読み直す（更新しても前回の続きが消えない）。
 */
private fun JSONObject.readTextSegments(): List<TextSegment> {
    val array = optJSONArray(VlogClipKeys.TEXTS)
        ?: return listOf(TextSegment(0L, optString(VlogClipKeys.LEGACY_USER_TEXT, DEFAULT_HITOKOTO)))

    // 昇順に直してから返す。区間の判定（textIndexAt / visibleTextSpans）は
    // 「前から順に並んでいる」前提で書かれているので、並びが崩れていると
    // ひとことが拾えない区間ができ、書き出しから文字が消える。
    val segments = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        TextSegment(
            startMs = item.optLong(VlogClipKeys.START_MS),
            text = item.optString(VlogClipKeys.TEXT, DEFAULT_HITOKOTO)
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

/**
 * [VlogClip.sortKeyMs] 用。[VideoMetadataReader.formatDate]/[formatTime] と同じ書式
 * （"yyyy/MM/dd" + "HH:mm"、Locale.US、端末ローカルタイムゾーン）の逆変換。
 * 壊れていれば最後尾へ送るため [Long.MAX_VALUE] を返す。
 */
private fun parseShotAtText(dateText: String, timeText: String): Long = runCatching {
    java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
        .parse("$dateText $timeText")?.time
}.getOrNull() ?: Long.MAX_VALUE

data class VideoMeta(
    val timeText: String,
    val dateText: String,
    val shotAtMillis: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int
)
