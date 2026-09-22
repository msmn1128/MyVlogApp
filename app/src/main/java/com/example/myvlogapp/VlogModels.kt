package com.example.myvlogapp

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
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
    // 解像度は持たない。書き出しは scale + pad で1920x1080のキャンバスへ入れるだけで
    // 入力サイズを知る必要がなく、プレビューもPlayerViewが動画から直接読むため
    val texts: List<TextSegment> = listOf(TextSegment()),
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val shotAtMillis: Long = 0L, // 撮影/作成日時（並び替えの基準）。0は未取得・旧データ
    val isMuted: Boolean = false, // このクリップの音声を書き出しで無音にするか
    /**
     * 撮影時刻を、動画自体が持つ確かな手がかり（作成日時メタデータ・ファイル名・更新日時など）から
     * 取れたか。falseは、手がかりが足りず追加時刻などで代用した値で、次回の復元時に取り直す
     * （[com.example.myvlogapp.VlogViewModel]）。保存データに無い旧データはfalse扱い。
     */
    val shotAtReliable: Boolean = true,
    /**
     * [shotAtReliable]がfalseのクリップについて、動画から取り直すのを一度試したか。
     *
     * 手がかりが本当に何も無い動画（作成日時もファイル名も更新日時も取れないもの）は、
     * 何度取り直しても確かな値にならない。これが無いと、そういう動画が1本でも
     * タイムラインに残っている限り、起動のたびに全部を開き直す処理が走り続ける
     * （100本なら数秒、画面には何も出ない）。一度試したら二度目は行わない目印。
     */
    val shotAtRefreshed: Boolean = false
) {
    val trimmedDurationMs: Long get() = trimmedDurationMs(startMs, endMs)
    val isValid: Boolean get() = durationMs > 0 && endMs > startMs

    /**
     * 撮影日時順の並び替えに使うキー。
     * [shotAtMillis] が無い旧データは [dateText]/[timeText] から逆算し、
     * それも壊れていれば最後尾へ送る（並びを壊さないため）。
     */
    val sortKeyMs: Long get() = if (shotAtMillis > 0L) shotAtMillis else parseShotAtText(dateText, timeText)

    /**
     * 書き出しでこのクリップの音声を無音にすべきか。
     * クリップ個別の[isMuted]と、タイムライン全体のミュート（呼び出し側が持つ
     * 状態なので引数で受け取る）のどちらか一方でも立っていれば無音にする。
     */
    fun isSilentInExport(timelineMuted: Boolean): Boolean = isMuted || timelineMuted

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
        texts.getOrNull(textIndexAt(positionMs))?.text ?: ""

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
        fun fromJson(json: JSONObject, id: Long): VlogClip {
            val durationMs = json.getLong(VlogClipKeys.DURATION_MS).coerceAtLeast(0L)
            // トリム位置は「0 <= start <= end <= 尺」に正規化してから入れる。
            // 保存データが壊れていて end < start になっていると、波形をタップした瞬間に
            // coerceIn(min > max) で例外になり、画面ごと落ちる。読めた値は活かしつつ、
            // 前後が入れ替わっている分だけを直す（ひとことや並び順は巻き添えにしない）。
            val startMs = json.getLong(VlogClipKeys.START_MS).coerceIn(0L, durationMs)
            val endMs = json.getLong(VlogClipKeys.END_MS).coerceIn(startMs, durationMs)
            return VlogClip(
                id = id,
                uri = Uri.parse(json.getString(VlogClipKeys.URI)),
                timeText = json.getString(VlogClipKeys.TIME_TEXT),
                dateText = json.getString(VlogClipKeys.DATE_TEXT),
                durationMs = durationMs,
                texts = json.readTextSegments(),
                startMs = startMs,
                endMs = endMs,
                // 並び替え機能を追加する前の保存データにはキー自体が無いので optLong で0にフォールバック
                shotAtMillis = json.optLong(VlogClipKeys.SHOT_AT_MILLIS),
                // ミュート機能を追加する前の保存データにはキー自体が無いので optBoolean でfalseにフォールバック
                isMuted = json.optBoolean(VlogClipKeys.IS_MUTED, false),
                // 確かさを持たせる前の保存データにはキー自体が無い。追加時の手がかりが足りなかった値が
                // 混ざっている可能性があるので、falseにして次回の復元時に取り直させる
                shotAtReliable = json.optBoolean(VlogClipKeys.SHOT_AT_RELIABLE, false),
                // 取り直し済みの目印を持たせる前の保存データにはキー自体が無い。falseにして
                // 一度だけ取り直させる（そこで取れなければ、以後は試さない）
                shotAtRefreshed = json.optBoolean(VlogClipKeys.SHOT_AT_REFRESHED, false)
            )
        }
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
    const val TEXTS = "texts"
    const val TEXT = "text"
    const val START_MS = "startMs"
    const val END_MS = "endMs"
    const val SHOT_AT_MILLIS = "shotAtMillis"
    const val IS_MUTED = "isMuted"
    const val SHOT_AT_RELIABLE = "shotAtReliable"
    const val SHOT_AT_REFRESHED = "shotAtRefreshed"

    /** 区間(texts)を持たせる前の旧バージョンで使われていたキー。読み込み専用の後方互換 */
    const val LEGACY_USER_TEXT = "userText"
}

/** [VlogClip] をJSONへ。ClipStoreの自動保存・一時保存の両方で同じ形を使う */
fun VlogClip.toJson(): JSONObject = JSONObject().apply {
    put(VlogClipKeys.URI, uri.toString())
    put(VlogClipKeys.TIME_TEXT, timeText)
    put(VlogClipKeys.DATE_TEXT, dateText)
    put(VlogClipKeys.DURATION_MS, durationMs)
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
    put(VlogClipKeys.IS_MUTED, isMuted)
    put(VlogClipKeys.SHOT_AT_RELIABLE, shotAtReliable)
    put(VlogClipKeys.SHOT_AT_REFRESHED, shotAtRefreshed)
}

/**
 * ひとことの区間を読む。
 *
 * 区間を持たせる前のバージョンで保存された分は [VlogClipKeys.LEGACY_USER_TEXT] しか
 * 無いので、その1件を先頭区間として読み直す（更新しても前回の続きが消えない）。
 */
private fun JSONObject.readTextSegments(): List<TextSegment> {
    val array = optJSONArray(VlogClipKeys.TEXTS)
        ?: return listOf(TextSegment(0L, optString(VlogClipKeys.LEGACY_USER_TEXT, "")))

    // 昇順に直してから返す。区間の判定（textIndexAt / visibleTextSpans）は
    // 「前から順に並んでいる」前提で書かれているので、並びが崩れていると
    // ひとことが拾えない区間ができ、書き出しから文字が消える。
    val segments = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        TextSegment(
            startMs = item.optLong(VlogClipKeys.START_MS),
            text = item.optString(VlogClipKeys.TEXT, "")
        )
    }.sortedBy { it.startMs }

    if (segments.isEmpty()) return listOf(TextSegment())

    // 先頭が0から始まらないと textAt が拾えない区間ができてしまう。
    // 全区間を白紙に差し替えると、1件目のstartMsが壊れているだけで残り全部の
    // ひとこと文言まで消えるので、先頭の位置だけ0へ直し、文言はそのまま残す。
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
 *
 * [text] の既定値は空文字。動画追加直後に触らなければプレビュー・書き出しの
 * どちらにも何も焼き込まれない（空行はdrawtextを描かないので[ExportTextFiles.writeSpanTextFiles]側で
 * 自然に何も出ない）。入力欄には[DEFAULT_HITOKOTO]をplaceholderとしてグレー表示するだけに留め、
 * タイムラインのタイル表示（未入力時の目印）は[DEFAULT_HITOKOTO]へのifBlankフォールバックで補う。
 */
data class TextSegment(
    val startMs: Long = 0L,
    val text: String = ""
)

/** [VlogClip.visibleTextSpans] の結果。number は画面に出す 1,2,3… の通し番号 */
data class TextSpan(
    val number: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * 既存の並び（[current]）はそのままに、新規クリップ（[added]）だけを
 * 撮影/作成日時（[VlogClip.sortKeyMs]）の位置へ差し込む。
 * VlogViewModel から切り出したもの（ViewModel抜きで単体テストできるようにするため）。
 *
 * @return 差し込み後の全件リストと、ExoPlayerのプレイリストへ同じ操作を
 *   再現するための (挿入先index, クリップ) のペア（indexが小さい順）
 */
internal fun mergeByShotAt(
    current: List<VlogClip>,
    added: List<VlogClip>
): Pair<List<VlogClip>, List<Pair<Int, VlogClip>>> {
    val result = current.toMutableList()
    val insertions = mutableListOf<Pair<Int, VlogClip>>()
    added.sortedBy { it.sortKeyMs }.forEach { clip ->
        val index = result.indexOfFirst { it.sortKeyMs > clip.sortKeyMs }
            .let { if (it < 0) result.size else it }
        result.add(index, clip)
        insertions += index to clip
    }
    return result to insertions
}

/**
 * 区間ごと移動（[com.example.myvlogapp.VlogViewModel.moveTrim]）で、実際にずらせる量。
 *
 * トリム範囲とひとことの区切りは「相対位置を保ったままひとかたまりで動く」のが狙いなので、
 * 区切りだけを1つずつ範囲へ丸めてはいけない。以前は各区切りを `coerceIn(1L, durationMs)` で
 * 丸めていたため、トリムより手前に残っている区切り（頭を落としたあとの分）を含むクリップを
 * 大きく左へ動かすと、それらが先頭付近へ潰れて相対位置が失われ、「もとに戻す」以外で
 * 復元できなくなっていた。代わりに、全部が同じ量で動けるところまで[requested]自体を詰める。
 *
 * 先頭の区間（`startMs == 0`）は動画そのものの頭なので動かさない。よって他の区切りの下限は
 * [MIN_TEXT_SEGMENT_MS]、上限は動画の尺。すでにその範囲を外れている保存データを
 * 動かせなくしてしまわないよう、許容範囲には必ず0（＝動かさない）を含める。
 *
 * @param requested トリム開始位置の移動量（動画の範囲へクランプ済み）
 * @return 実際にずらす量。動かせる余地が無ければ [requested] のまま0に近い値になる
 */
internal fun clampTimelineShift(
    texts: List<TextSegment>,
    requested: Long,
    durationMs: Long
): Long {
    // 移動の対象になるのは、先頭（絶対位置0）以外の区切りだけ
    val moving = texts.filter { it.startMs != 0L }
    if (moving.isEmpty()) return requested

    val lowest = moving.minOf { it.startMs }
    val highest = moving.maxOf { it.startMs }
    val lo = minOf(MIN_TEXT_SEGMENT_MS - lowest, 0L)
    val hi = maxOf(durationMs - highest, 0L)
    return requested.coerceIn(lo, hi)
}

/**
 * [VlogClip.sortKeyMs] 用。[VideoMetadataReader.formatDate]/[formatTime] と同じ書式
 * （"yyyy/MM/dd" + "HH:mm"、Locale.US、端末ローカルタイムゾーン）の逆変換。
 * 壊れていれば最後尾へ送るため [Long.MAX_VALUE] を返す。
 */
private fun parseShotAtText(dateText: String, timeText: String): Long = runCatching {
    java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
        .parse("$dateText $timeText")?.time
}.getOrNull() ?: Long.MAX_VALUE

/**
 * クリップの[VlogClip.id]を発行する。プロセス内で重複しない通し番号。
 *
 * idはLazyRowのkeyと、撮影時刻の取り直し時の突き合わせに使う。以前は
 * `System.nanoTime() + index` で作っていたが、「同じ瞬間に2箇所から発行されても
 * ぶつからない」ことが時計の分解能頼みで、正しさを読み取れなかった。
 * 単調増加のカウンタなら、ぶつからないことが定義から明らかになる。
 */
private val clipIdCounter = AtomicLong(0L)

internal fun nextClipId(): Long = clipIdCounter.incrementAndGet()

data class VideoMeta(
    val timeText: String,
    val dateText: String,
    val shotAtMillis: Long,
    /** 撮影時刻を確かな手がかりから取れたか（[VlogClip.shotAtReliable]） */
    val shotAtReliable: Boolean,
    val durationMs: Long
)
