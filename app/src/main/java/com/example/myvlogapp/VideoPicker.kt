package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * アプリ内のギャラリー選択画面。
 *
 * システムのフォトピッカーを使わず自前で組んでいるのは、フォトピッカーが返すURIが
 * プロセスの生存中しか有効でなく、アプリを閉じると編集の続きを復元できないため。
 * READ_MEDIA_VIDEO を取ってMediaStoreのURIを直接扱えば、動画をコピーしなくても
 * 次回起動時にそのまま読み込める。
 */

/** ギャラリー内の動画1件 */
data class GalleryVideo(
    val uri: Uri,
    val name: String,
    val durationMs: Long
)

/** この端末で動画一覧に必要な権限。Android 14以降は「選択した項目のみ」も含む */
val mediaPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else ->
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * 動画を一覧できる状態か。
 *
 * 「選択した項目のみ許可」の場合は READ_MEDIA_VIDEO が拒否のままになるため、
 * どれか1つでも許可されていれば一覧できると判断する。
 */
fun hasMediaAccess(context: Context): Boolean = mediaPermissions.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * 「選択した項目のみ許可」の状態か（Android 14以降）。
 *
 * この状態では一部の動画しか一覧に出ないため、対象を選び直す導線が必要になる。
 * 権限をもう一度リクエストすると、システムの選択画面が再表示される。
 */
fun hasPartialMediaAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VIDEO
            ) != PackageManager.PERMISSION_GRANTED

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
        }.getOrDefault(emptyList())
    }

/**
 * サムネイル。1件ずつ非同期に読み込む。
 * loadThumbnail は API 29 以降。それ以前は無地のタイルにファイル名だけ出す。
 */
@Composable
private fun rememberThumbnail(uri: Uri): Bitmap? {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
                } else {
                    null
                }
            }.getOrNull()
        }
    }.value
}

/**
 * @param reloadToken 値が変わると一覧を取り直す。許可する動画を選び直した直後に使う
 */
@Composable
fun GalleryPickerDialog(
    reloadToken: Int,
    onDismiss: () -> Unit,
    onPick: (List<Uri>) -> Unit,
    onUseFilePicker: () -> Unit,
    onChangeSelection: () -> Unit
) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<GalleryVideo>?>(null) }
    val selected = remember { mutableStateListOf<Uri>() }
    val isPartial = remember(reloadToken) { hasPartialMediaAccess(context) }

    LaunchedEffect(reloadToken) {
        videos = null
        videos = queryGalleryVideos(context)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("動画を選ぶ", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // MediaStoreに出てこない場所（Downloadなど）の動画はこちらから
                        TextButton(
                            onClick = onUseFilePicker,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("ファイル", fontSize = 13.sp) }
                    }
                }

                // 一部の動画だけ許可している場合は、対象を選び直せるようにする
                if (isPartial) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "許可した動画のみ表示中",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onChangeSelection,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("選択を変更", fontSize = 13.sp) }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val list = videos
                    when {
                        list == null -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )

                        list.isEmpty() -> Text(
                            "動画が見つかりませんでした",
                            modifier = Modifier.align(Alignment.Center)
                        )

                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 104.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(list, key = { it.uri.toString() }) { video ->
                                val index = selected.indexOf(video.uri)
                                VideoTile(
                                    video = video,
                                    selectionOrder = if (index >= 0) index + 1 else null,
                                    onClick = {
                                        if (index >= 0) selected.removeAt(index)
                                        else selected.add(video.uri)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("キャンセル")
                    }
                    Button(
                        onClick = { onPick(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (selected.isEmpty()) "追加" else "${selected.size} 件を追加")
                    }
                }
            }
        }
    }
}

/** 選んだ順の番号を出すので、追加後の並び順が事前に分かる */
@Composable
private fun VideoTile(
    video: GalleryVideo,
    selectionOrder: Int?,
    onClick: () -> Unit
) {
    val thumbnail = rememberThumbnail(video.uri)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selectionOrder != null) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = video.name,
                fontSize = 10.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(6.dp)
            )
        }

        Text(
            text = formatSeconds(video.durationMs),
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )

        if (selectionOrder != null) {
            Text(
                text = "$selectionOrder",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
