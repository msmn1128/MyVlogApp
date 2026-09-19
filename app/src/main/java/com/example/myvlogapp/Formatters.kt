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
 * 保存名の既定値 "M/d" を、同名がすでにあれば「M/d (1)」のように連番を付けて返す。
 *
 * 動画の書き出しファイル名（VlogExporter.buildDisplayName）と同じ考え方。
 */
fun defaultSaveName(millis: Long, existingNames: Collection<String>): String {
    val base = SimpleDateFormat("M/d", Locale.getDefault()).format(millis)
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
