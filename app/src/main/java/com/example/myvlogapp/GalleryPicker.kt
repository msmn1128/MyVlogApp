package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * アプリ内のギャラリー選択画面。
 *
 * システムのフォトピッカーを使わず自前で組んでいるのは、フォトピッカーが返すURIが
 * プロセスの生存中しか有効でなく、アプリを閉じると編集の続きを復元できないため。
 * READ_MEDIA_VIDEO を取ってMediaStoreのURIを直接扱えば、動画をコピーしなくても
 * 次回起動時にそのまま読み込める。
 *
 * 権限判定は MediaAccess.kt、MediaStoreへの問い合わせは GalleryRepository.kt に
 * 分離してあり、このファイルにはCompose UIだけが残る。
 */

/** サムネイル画像の要求サイズ(px)。グリッドのタイル自体はdpだが、こちらは実ピクセルで指定する */
private val THUMBNAIL_SIZE = Size(320, 320)

/** グリッドタイルの最小幅 */
private val TILE_MIN_SIZE = 104.dp

/** タイル・選択枠に共通で使う角丸 */
private val TILE_SHAPE = RoundedCornerShape(6.dp)

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
                    context.contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, null)
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
                GalleryPickerHeader(onUseFilePicker = onUseFilePicker)

                // 一部の動画だけ許可している場合は、対象を選び直せるようにする
                if (isPartial) {
                    PartialAccessBanner(onChangeSelection = onChangeSelection)
                }

                VideoGrid(
                    videos = videos,
                    selected = selected,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                Spacer(Modifier.height(8.dp))

                GalleryPickerFooter(
                    selectedCount = selected.size,
                    onDismiss = onDismiss,
                    onPick = { onPick(selected.toList()) }
                )
            }
        }
    }
}

@Composable
private fun GalleryPickerHeader(onUseFilePicker: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("動画を選ぶ", style = MaterialTheme.typography.titleMedium)
        // MediaStoreに出てこない場所（Downloadなど）の動画はこちらから
        TextButton(
            onClick = onUseFilePicker,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { Text("ファイル", fontSize = 13.sp) }
    }
}

@Composable
private fun PartialAccessBanner(onChangeSelection: () -> Unit) {
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

@Composable
private fun VideoGrid(
    videos: List<GalleryVideo>?,
    selected: SnapshotStateList<Uri>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when {
            videos == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            videos.isEmpty() -> Text(
                "動画が見つかりませんでした",
                modifier = Modifier.align(Alignment.Center)
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TILE_MIN_SIZE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(videos, key = { it.uri.toString() }) { video ->
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
}

@Composable
private fun GalleryPickerFooter(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onPick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            Text("キャンセル")
        }
        Button(
            onClick = onPick,
            enabled = selectedCount > 0,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (selectedCount == 0) "追加" else "$selectedCount 件を追加")
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
            .clip(TILE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selectionOrder != null) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, TILE_SHAPE)
                } else {
                    Modifier
                }
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
