package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// =====================================================================================
// メタデータ取得
//
// VlogModels.kt から分離。Context/MediaMetadataRetriever/MediaStore に依存する
// Android I/O専用の処理をここに集める（VlogModels.kt側は純粋データのみにするため）。
// =====================================================================================

/**
 * 動画から撮影日時・長さ・解像度を取得する。
 *
 * 撮影日時メタデータ（creation_time）は、SNS経由で共有された動画や
 * PCで変換した動画では失われていることが多いため、
 * 取得できない場合は MediaStore の追加日時にフォールバックする。
 */
fun getVideoMetadata(context: Context, uri: Uri): VideoMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L

        val shotAtMillis = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            ?.let { parseCreationTime(it) }
            ?: queryMediaStoreDateMillis(context, uri))
            ?: System.currentTimeMillis()

        // 生の幅・高さは回転情報(90/270度)を反映していないため補正する。
        // 縦持ち撮影の動画は内部的に横長のままrotation=90が入っていることが多い。
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: CANVAS_WIDTH
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: CANVAS_HEIGHT
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val (width, height) =
            if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight

        VideoMeta(
            timeText = formatTime(shotAtMillis),
            dateText = formatDate(shotAtMillis),
            shotAtMillis = shotAtMillis,
            durationMs = durationMs,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1)
        )
    } catch (e: Exception) {
        // メタデータが1件も取れない動画（壊れたファイル、非対応コーデックなど）。
        // 「取得できなかった」こと自体は空リストと違って原因を追いたいことが多いのでログに残す。
        Log.w(LOG_TAG, "動画のメタデータを取得できませんでした: $uri", e)
        val fallbackMillis = System.currentTimeMillis()
        VideoMeta(formatTime(fallbackMillis), formatDate(fallbackMillis), fallbackMillis, 0L, CANVAS_WIDTH, CANVAS_HEIGHT)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun parseCreationTime(raw: String): Long? = runCatching {
    SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(raw)?.time
}.getOrNull()

private fun queryMediaStoreDateMillis(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(
        uri, arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) * 1000L else null
    }
}.getOrNull()

/**
 * 撮影時刻 "HH:mm"（24時間表記）。
 *
 * Locale.USを明示するのは、この文字列が動画へ焼き込まれるため。
 * 既定ロケールに任せると、アラビア語ロケールなどで数字の字形が変わってしまい、
 * 書き出しに使うフォントに字形が無いと文字化けする（表示だけの[formatSeconds]と同じ方針）。
 * タイムゾーンは既定のまま＝端末のローカル時刻で表示する。
 */
private fun formatTime(millis: Long?): String =
    millis?.let { SimpleDateFormat("HH:mm", Locale.US).format(it) } ?: "00:00"

/** 撮影日 "yyyy/MM/dd"。書き出しファイル名にも使うためロケール固定（理由は[formatTime]と同じ） */
private fun formatDate(millis: Long?): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.US).format(millis ?: System.currentTimeMillis())
