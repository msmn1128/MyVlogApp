package com.example.myvlogapp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import com.example.myvlogapp.CANVAS_HEIGHT
import com.example.myvlogapp.CANVAS_WIDTH
import com.example.myvlogapp.LOG_TAG
import com.example.myvlogapp.VideoMeta

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
 * PCで変換した動画、画面録画では失われていることが多いため、
 * 取得できない場合は[guessShotAt]の順で、動画の撮影時刻を示す他の手がかりを探す。
 *
 * それも取れない場合は[fallbackMillis]を使う。複数動画を並列取得する際に
 * ここで System.currentTimeMillis() を都度呼ぶと、並列処理の完了タイミング
 * （実行順とは無関係）で撮影時刻が決まってしまい、同時刻になったり
 * 選択順と違う並びになったりするため、呼び出し側で選択順に基づいて
 * 一意に決めた値を渡してもらう。
 */
fun getVideoMetadata(context: Context, uri: Uri, fallbackMillis: Long): VideoMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L

        val shotAt = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            ?.let { parseCreationTime(it) }
            ?.let { ShotAt(it, "creation_time", reliable = true) }
            ?: guessShotAt(context, uri)
        val shotAtMillis = shotAt?.millis ?: fallbackMillis
        // 撮影時刻がどこから取れたか。「時刻がおかしい」という報告の原因を追うために残す。
        Log.i(LOG_TAG, "撮影時刻の取得元: ${shotAt?.source ?: "なし（追加時刻で代用）"} / $uri")

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
            shotAtReliable = shotAt?.reliable == true,
            durationMs = durationMs,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1)
        )
    } catch (e: Exception) {
        // メタデータが1件も取れない動画（壊れたファイル、非対応コーデックなど）。
        // 「取得できなかった」こと自体は空リストと違って原因を追いたいことが多いのでログに残す。
        Log.w(LOG_TAG, "動画のメタデータを取得できませんでした: $uri", e)
        VideoMeta(
            formatTime(fallbackMillis), formatDate(fallbackMillis), fallbackMillis,
            shotAtReliable = false, 0L, CANVAS_WIDTH, CANVAS_HEIGHT
        )
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 埋め込みの作成日時（creation_time）を読む。書式は "20260919T093501.000Z" が標準だが、
 * 機種によっては小数部やZが付かないことがあるため、どちらも受け付ける。
 *
 * MP4の日時は1904年1月1日が起点で、作成日時が未設定の動画（変換ツールなどで作られたもの）は
 * 0＝1904/01/01と読める。これを撮影日時として採用すると、日付・並び順・ファイル名が
 * 1904年になってしまうため、エポック（1970年）以前は「無い」として扱い、
 * 他の手がかりへフォールバックさせる。
 */
internal fun parseCreationTime(raw: String): Long? {
    val m = CREATION_TIME_REGEX.matchEntire(raw.trim()) ?: return null
    val g = m.groupValues
    val millis = g[7].padEnd(3, '0').toIntOrNull() ?: 0
    return calendarMillis(
        TimeZone.getTimeZone("UTC"),
        g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), millis
    )?.takeIf { it > 0L }
}

private val CREATION_TIME_REGEX =
    Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.(\d{1,3}))?Z?""")

/** 存在しない日時（13月、25時など）は例外にせずnullを返す */
private fun calendarMillis(
    zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, millis: Int
): Long? = runCatching {
    Calendar.getInstance(zone, Locale.US).apply {
        isLenient = false
        clear()
        set(year, month - 1, day, hour, minute, second)
        set(Calendar.MILLISECOND, millis)
    }.timeInMillis
}.getOrNull()

/**
 * 撮影時刻と、それをどこから取ったか（ログ用）。
 * [reliable]は動画自体が持つ手がかりか。追加日時（端末に取り込んだ時刻）は、
 * まとめて取り込むと全部同じになるので確かとは言えない。
 */
private class ShotAt(val millis: Long, val source: String, val reliable: Boolean)

/**
 * creation_time が無い動画の撮影時刻を、他の手がかりから探す。優先順位：
 *  1. MediaStoreの撮影日時（DATE_TAKEN）。ms精度の実撮影時刻
 *  2. ファイル名に含まれる日時（"Screen_Recording_20260901-101500" など）。
 *     画面録画やカメラアプリの命名規則で、撮影時刻そのもの
 *  3. ファイルの更新日時。コピーしても保たれることが多い
 *  4. 端末への追加日時（DATE_ADDED）。あくまで最後の手段
 *
 * 追加日時を最後にするのは、まとめて取り込んだ動画は全部同じ値になり、
 * 撮影時刻が全部同じに見えてしまうため。
 *
 * 列ごとに別クエリ・別runCatchingにしているのは、プロバイダによっては片方の列名を
 * 認識できずクエリ自体が例外になり、本来取れるはずのもう片方まで失ってしまうため。
 */
private fun guessShotAt(context: Context, uri: Uri): ShotAt? =
    queryLongColumn(context, uri, MediaStore.MediaColumns.DATE_TAKEN)
        ?.takeIf { it > 0L }?.let { ShotAt(it, "MediaStore撮影日時", reliable = true) }
        ?: queryDisplayName(context, uri)
            ?.let { parseShotTimeFromFileName(it) }?.let { ShotAt(it, "ファイル名", reliable = true) }
        ?: queryLastModifiedMillis(context, uri)?.let { ShotAt(it, "ファイル更新日時", reliable = true) }
        ?: queryLongColumn(context, uri, MediaStore.MediaColumns.DATE_ADDED)
            ?.takeIf { it > 0L }?.let { ShotAt(it * 1000L, "追加日時", reliable = false) }

/**
 * ファイルの更新日時（ms）。MediaStoreのURIは DATE_MODIFIED（秒）、
 * ファイル選択（SAF）のURIは Document.COLUMN_LAST_MODIFIED（ms）で持っている。
 */
private fun queryLastModifiedMillis(context: Context, uri: Uri): Long? =
    queryLongColumn(context, uri, MediaStore.MediaColumns.DATE_MODIFIED)
        ?.takeIf { it > 0L }?.let { it * 1000L }
        ?: queryLongColumn(context, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?.takeIf { it > 0L }

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
}.getOrNull()

private fun queryLongColumn(context: Context, uri: Uri, column: String): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull()

/** "20260901-101500" / "20260901_101500" / "PXL_20260901_101500123" のような、日付と時刻が続く形 */
private val FILE_NAME_COMPACT =
    Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})[-_ T]?(\d{2})(\d{2})(\d{2})(?:\d{3})?(?!\d)""")

/** "2026-09-01 10-15-00" / "2026.09.01_10.15.00" / "2026-09-01 at 10.15.00 AM" のような、区切りのある形 */
private val FILE_NAME_SEPARATED = Regex(
    """(?<!\d)(\d{4})[-_.](\d{2})[-_.](\d{2})[-_ T.]+(?:at\s+)?(\d{1,2})[-_.:](\d{2})[-_.:](\d{2})(?!\d)(?:\s*([AaPp][Mm]))?"""
)

/**
 * ファイル名に含まれる撮影日時を読む（端末のローカル時刻として解釈する）。
 * 見つからない、または存在しない日時（月が13など）のときはnull。
 * 日付だけで時刻が無い名前（"IMG-20260901-WA0001" など）は時刻が分からないので対象外。
 */
internal fun parseShotTimeFromFileName(name: String): Long? {
    for (regex in listOf(FILE_NAME_COMPACT, FILE_NAME_SEPARATED)) {
        for (m in regex.findAll(name)) {
            val g = m.groupValues
            val year = g[1].toInt()
            if (year !in 1990..2100) continue

            var hour = g[4].toInt()
            when (g.getOrNull(7)?.lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            calendarMillis(
                TimeZone.getDefault(), year, g[2].toInt(), g[3].toInt(), hour, g[5].toInt(), g[6].toInt(), 0
            )?.let { return it }
        }
    }
    return null
}

/**
 * 撮影時刻 "HH:mm"（24時間表記）。
 *
 * Locale.USを明示するのは、この文字列が動画へ焼き込まれるため。
 * 既定ロケールに任せると、アラビア語ロケールなどで数字の字形が変わってしまい、
 * 書き出しに使うフォントに字形が無いと文字化けする（表示だけの[formatSeconds]と同じ方針）。
 * タイムゾーンは既定のまま＝端末のローカル時刻で表示する。
 */
private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(millis)

/** 撮影日 "yyyy/MM/dd"。タイトルカードへ焼き込むためロケール固定（理由は[formatTime]と同じ） */
private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.US).format(millis)
