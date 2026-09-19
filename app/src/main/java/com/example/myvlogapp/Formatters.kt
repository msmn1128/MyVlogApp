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
 * 動画を追加したあとに出す、スキップの通知文。どちらも0件ならnull（通知しない）。
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
 * 保存名の既定値 "M/d"。同名がすでにあれば「M/d (1)」のように連番を付ける（[uniqueSaveName]）。
 */
fun defaultSaveName(millis: Long, existingNames: Collection<String>): String =
    uniqueSaveName(SimpleDateFormat("M/d", Locale.getDefault()).format(millis), existingNames)

/**
 * [base]と同じ名前が[existingNames]にすでにあれば、「base (1)」「base (2)」のように
 * 空いている最初の連番を付けて返す。無ければ[base]のまま。
 *
 * 一時保存の名前（既定値・自分で打った名前のどちらも）に使う。
 * 動画の書き出しファイル名（VlogExporter.buildDisplayName）と同じ考え方。
 */
fun uniqueSaveName(base: String, existingNames: Collection<String>): String {
    val taken = existingNames.toSet()

    var candidate = base
    var index = 1
    while (candidate in taken) {
        candidate = "$base ($index)"
        index++
    }
    return candidate
}

/**
 * 尺の表示 "m:ss"。タイムラインとギャラリーで表記を揃えるためここに1本だけ置く。
 *
 * Locale.USを明示するのは、アラビア語ロケールなど数字の字形が違う環境でも
 * 常に半角のアラビア数字で表示するため（既定ロケールに任せると環境依存になる）。
 */
fun formatSeconds(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
