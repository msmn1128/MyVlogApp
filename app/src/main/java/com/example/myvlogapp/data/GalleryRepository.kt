package com.example.myvlogapp.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.myvlogapp.LOG_TAG

// =====================================================================================
// ギャラリー取得
//
// GalleryPicker.kt から分離。MediaStoreへのクエリだけを行う非Compose層。
// =====================================================================================

/** ギャラリー内の動画1件 */
data class GalleryVideo(
    val uri: Uri,
    val name: String,
    val durationMs: Long
)

/** 端末内の動画を新しい順に取得する */
suspend fun queryGalleryVideos(context: Context): List<GalleryVideo> =
    withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )

        runCatching {
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        add(
                            GalleryVideo(
                                uri = ContentUris.withAppendedId(collection, id),
                                name = cursor.getString(nameColumn).orEmpty(),
                                durationMs = cursor.getLong(durationColumn)
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse { e ->
            // 権限が無い・MediaStoreへのアクセスに失敗した場合など。
            // 「動画が見つかりませんでした」と「権限が無くて読めなかった」を
            // 呼び出し元のUIだけからは区別できないため、原因はログに残しておく。
            Log.w(LOG_TAG, "ギャラリーの動画一覧を取得できませんでした", e)
            emptyList()
        }
    }
