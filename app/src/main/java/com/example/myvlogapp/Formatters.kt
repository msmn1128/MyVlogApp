package com.example.myvlogapp

import java.text.SimpleDateFormat
import java.util.Locale

// =====================================================================================
// 表示ヘルパー
//
// VlogModels.kt から分離。UI表示用の文字列整形だけをここに集める。
// =====================================================================================

/** 一時保存の日時表示 "M/d HH:mm"。一覧の見出しで使う */
fun formatSavedAt(millis: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(millis)

/**
 * 動画を追加したあとに出す、スキップの通知文。いずれも0件ならnull（通知しない）。
 *
 * @param alreadyAdded すでにタイムラインにあったため追加しなかった件数
 * @param unreadable 長さなどを読み取れなかったため追加しなかった件数（壊れたファイル、
 *   コピー途中のファイルなど）
 * @param overLimit クリップ数の上限（[limit]）を超えるため追加しなかった件数
 */
fun addSkipMessage(
    alreadyAdded: Int,
    unreadable: Int,
    overLimit: Int = 0,
    limit: Int = MAX_CLIPS
): String? = listOfNotNull(
    "$alreadyAdded 件は追加済みのためスキップしました".takeIf { alreadyAdded > 0 },
    "$unreadable 件は読み込めなかったので追加しませんでした（もう一度選び直してください）"
        .takeIf { unreadable > 0 },
    "$overLimit 件は上限（${limit}本）を超えるため追加しませんでした".takeIf { overLimit > 0 }
).joinToString("\n").ifEmpty { null }

/**
 * 一時保存を読み出して、いまのタイムラインを置き換えてよいか。
 *
 * 保存内の動画が1本も読めないのに置き換えると、作業中のタイムラインが空になってしまう
 * （動画が削除・移動された、アクセス権限が取り消された場合など）。その場合は置き換えない。
 * 保存自体が空（読めなかった動画も無い）のときは、そのまま読み出せる。
 *
 * @param loaded 読み出せた動画の本数
 * @param dropped 見つからなかった（読めなかった）動画の本数
 */
fun canReplaceWithProject(loaded: Int, dropped: Int): Boolean = loaded > 0 || dropped == 0

/** 一時保存を読み出したあとの通知文。もとに戻せることも添える */
fun projectLoadedMessage(dropped: Int): String =
    if (dropped > 0) {
        "読み出しました（$dropped 件の動画は見つかりませんでした）。もとに戻すで読み出す前へ戻ります"
    } else {
        "読み出しました（もとに戻すで読み出す前へ戻ります）"
    }

/** 読める動画が1本も無くて読み出さなかったときの通知文（[canReplaceWithProject]がfalseのとき） */
fun projectUnreadableMessage(dropped: Int): String =
    "この保存の動画は $dropped 件とも見つからないため、読み出しませんでした" +
        "（移動・削除されたか、アクセス権限が取り消されています）"

/**
 * 保存名の既定値 "M/d"。同名がすでにあれば「M/d (1)」のように連番を付ける（[uniqueSaveName]）。
 */
fun defaultSaveName(millis: Long, existingNames: Collection<String>): String =
    uniqueSaveName(SimpleDateFormat("M/d", Locale.getDefault()).format(millis), existingNames)

/**
 * [base]と同じ名前が[existingNames]にすでにあれば、「base (1)」「base (2)」のように
 * 空いている最初の連番を付けて返す。無ければ[base]のまま。
 *
 * 一時保存の名前（既定値・自分で打った名前のどちらも）と、動画の書き出しファイル名
 * （[com.example.myvlogapp.export.VlogExporter]）の両方で使う。以前はそれぞれが
 * 同じwhileループを別々に持っていた。
 *
 * @param suffix 連番より後ろに付ける固定文字列。拡張子のように「連番の外側」に
 *   置きたいものを渡す（"Vlog_2026-09-20 (1).mp4" であって "Vlog_2026-09-20.mp4 (1)" ではない）。
 *   既定は空で、一時保存の名前はこちらを使う。
 */
fun uniqueSaveName(
    base: String,
    existingNames: Collection<String>,
    suffix: String = ""
): String {
    val taken = existingNames.toSet()

    var candidate = base + suffix
    var index = 1
    while (candidate in taken) {
        candidate = "$base ($index)$suffix"
        index++
    }
    return candidate
}

/**
 * 尺の表示 "m:ss"。タイムラインとギャラリーで表記を揃えるためここに1本だけ置く。
 *
 * 秒は四捨五入する。切り捨てだと、トリミングの範囲の表示が「0:03 〜 0:15（0:11）」
 * （実際は3.2〜15.1秒、11.9秒）のように、引き算と合わなく見えていた。長さの側は
 * [roundedTrimMs]で、丸めた両端の差にそろえる。
 *
 * Locale.USを明示するのは、アラビア語ロケールなど数字の字形が違う環境でも
 * 常に半角のアラビア数字で表示するため（既定ロケールに任せると環境依存になる）。
 */
fun formatSeconds(ms: Long): String {
    val totalSeconds = roundToSecondMs(ms) / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/**
 * トリミング後の長さの、表示用の値。両端をそれぞれ秒へ丸めてから差を取る。
 *
 * 長さそのもの（end−start）を丸めると、両端の表示の引き算と1秒ずれることがある
 * （3.5〜15.4秒は「0:04 〜 0:15」なのに、長さ11.9秒を丸めると「0:12」）。
 * 表示はだいたいの目安なので、見た目の引き算が必ず合う方を採る。書き出しの長さには使わないこと。
 */
fun roundedTrimMs(startMs: Long, endMs: Long): Long =
    (roundToSecondMs(endMs) - roundToSecondMs(startMs)).coerceAtLeast(0L)

/** 秒の単位へ四捨五入したミリ秒 */
private fun roundToSecondMs(ms: Long): Long = (ms + 500) / 1000 * 1000
