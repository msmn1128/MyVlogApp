package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import java.text.SimpleDateFormat
import java.util.Locale

// =====================================================================================
// 表示ヘルパー
//
// VlogModels.kt から分離。UI表示用の文字列整形だけをここに集める。
// =====================================================================================

/** 一時保存の日時表示 "M/d HH:mm"。保存名の既定値と一覧の見出しで同じ形にする */
fun formatSavedAt(millis: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(millis)

/** 尺の表示 "m:ss"。タイムラインとギャラリーで表記を揃えるためここに1本だけ置く */
fun formatSeconds(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
