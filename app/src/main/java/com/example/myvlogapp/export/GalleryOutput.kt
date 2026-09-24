package com.example.myvlogapp.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import com.example.myvlogapp.uniqueSaveName

// =====================================================================================
// 完成した動画のギャラリー（MediaStore）への保存。
//
// VlogExporter.kt から切り出したもの。getExternalFilesDir はアプリ専用領域で
// ユーザーから見えないため、MediaStore 経由で Movies/[OUTPUT_SUBDIRECTORY] へ保存する。
// =====================================================================================

/**
 * ギャラリー保存先のサブフォルダ名(Movies/以下)。
 * 孤児ファイル掃除（VlogExporter.cleanupOrphanedPendingFiles）の検索条件とも一致させる
 * ため internal にしてある。
 */
internal const val OUTPUT_SUBDIRECTORY = "MyVlogApp"

private const val COPY_BUFFER_SIZE = 64 * 1024

/** 掃除側（VlogExporter）と同じコレクションを見るため internal */
internal fun videoCollection() =
    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

/**
 * MP4のcreation_time用のISO 8601表記（UTC）。
 * 指定しないとメタデータが空（MP4の起点である1904/01/01と読める）になり、
 * 書き出した動画を再び取り込んだときに撮影日時が1904年になってしまう。
 */
internal fun creationTimeMetadata(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(millis)

/** ファイル名用の日付 "yyyy-MM-dd"（端末のローカル日付。Locale.USで数字の字形を固定） */
internal fun fileNameDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(millis)

/**
 * 完成した動画をギャラリー（Movies/[OUTPUT_SUBDIRECTORY]）へ保存する。
 *
 * @param createdAtMillis 動画の作成日時。ギャラリーが並び替えや日付表示に使う撮影日時へ入れる。
 * @return ギャラリーに保存された表示名
 */
internal suspend fun saveToGallery(
    context: Context,
    source: File,
    createdAtMillis: Long
): String {
    val resolver = context.contentResolver
    val displayName = buildDisplayName(context, createdAtMillis)
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.DATE_TAKEN, createdAtMillis)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(
            MediaStore.Video.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_MOVIES}/$OUTPUT_SUBDIRECTORY"
        )
        // 書き込み終わりで0に戻すまで、他アプリからは見えない（＝途中まで書けた
        // 壊れた動画がギャラリーに並ばない）
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val uri = resolver.insert(videoCollection(), values)
        ?: throw VlogExportException("ギャラリーへの保存に失敗しました")

    // コピー自体は中断ポイントを持たない同期I/Oなので、途中でキャンセルされても
    // 素通りしてコピーが完了してしまう（「中止した」のに保存済みになる不整合）。
    // バッファ単位でensureActive()を挟み、キャンセル時は挿入済みのMediaStore行を消す。
    // キャンセル以外の失敗（空き容量不足のIOExceptionなど）でも同様に消す。
    // 消さないとIS_PENDINGのまま残り、次回起動時の掃除まで壊れた項目が居座る。
    //
    // IS_PENDINGを0へ戻すところまでが「保存」。この更新も同じtryの中に入れる。
    // 外に置いていた頃は、ここで失敗するとユーザーには「書き出しに失敗しました」と
    // 出るのに、他アプリから見えない項目だけがギャラリーに残り続けていた。
    try {
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> copyCancellably(input, output) }
        } ?: throw VlogExportException("ギャラリーへの書き込みに失敗しました")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        // 1件も更新できなかったら失敗として扱う。戻り値を見ていなかった頃は、ここで失敗しても
        // 「保存しました」と出たうえ、IS_PENDINGのまま残った動画を、次の起動の掃除
        // （VlogExporter.cleanupOrphanedPendingFiles）が書き出し途中の残骸として消していた
        if (resolver.update(uri, values, null, null) == 0) {
            throw VlogExportException("ギャラリーへの保存に失敗しました")
        }
    } catch (e: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
    return displayName
}

private suspend fun copyCancellably(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
    }
}

/**
 * 保存するファイル名を決める。書き出しを始めた現在の日付から作り、
 * 「Vlog_2026-08-24.mp4」のような形にする。時刻はファイル名に入れず、
 * 動画自体の作成日時（メタデータ）だけが持つ。
 * タイトルカードの文言（撮影日や自由入力）は使わない。自由入力は改行やパス区切り文字などを
 * 含みうるため、ファイル名にはしないほうが安全で、書き出しの日付なら常に安全な文字だけで済む。
 *
 * 日付の区切りにハイフンを使うのは、ファイル名にスラッシュを含められないため
 * （パス区切りと解釈されて保存に失敗する）。
 *
 * 同じ日に複数回書き出したときは「Vlog_2026-08-24 (1).mp4」のように連番を付ける。
 */
private fun buildDisplayName(context: Context, createdAtMillis: Long): String {
    val base = "Vlog_${fileNameDate(createdAtMillis)}"
    return uniqueSaveName(base, existingDisplayNames(context, base), suffix = ".mp4")
}

/**
 * すでに保存済みの、同じ名前で始まる動画の一覧。
 *
 * 自分のアプリが作ったファイルは権限なしで参照できる。
 * 取得に失敗しても空集合を返せば連番なしの名前になるだけで、
 * MediaStore側でも名前の重複は解決されるため書き出し自体は成功する。
 */
private fun existingDisplayNames(context: Context, base: String): Set<String> = runCatching {
    val escapedBase = base.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%")
    context.contentResolver.query(
        videoCollection(),
        arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
        arrayOf("$escapedBase%"),
        null
    )?.use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }.orEmpty()
}.getOrDefault(emptySet())
