package com.example.myvlogapp.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.provider.Settings
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.myvlogapp.formatSeconds
import com.example.myvlogapp.data.GalleryVideo
import com.example.myvlogapp.data.hasMediaAccess
import com.example.myvlogapp.data.hasPartialMediaAccess
import com.example.myvlogapp.data.queryGalleryVideos
import com.example.myvlogapp.ui.components.VlogIcons

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

/** サムネイルのキャッシュの上限（バイト）。320x320のARGBが約400KBなので、約40枚ぶん */
private const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024

/**
 * 読み込んだサムネイルを、ギャラリー画面を開いている間だけ覚えておく。
 *
 * グリッドは画面外へ出たタイルを捨てるので、覚えておかないとスクロールして戻るたびに
 * 読み直しになり、しばらく無地のタイルが並ぶ。画面を閉じたら丸ごと捨てる
 * （[GalleryPickerDialog]の中でrememberしている）。
 */
private class ThumbnailCache : LruCache<Uri, Bitmap>(THUMBNAIL_CACHE_BYTES) {
    override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
}

/**
 * サムネイル。1件ずつ非同期に読み込む。
 * 取得できなかった動画（生成待ち・非対応コーデックなど）は無地のタイルに
 * ファイル名だけを出す。
 */
@Composable
private fun rememberThumbnail(uri: Uri, cache: ThumbnailCache): Bitmap? {
    val context = LocalContext.current
    return produceState(initialValue = cache.get(uri), uri) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, null)
            }.getOrNull()
        }?.also { cache.put(uri, it) }
    }.value
}

/**
 * 動画へのアクセスが許可されていなくても開く。そのときは一覧の代わりに、許可し直す導線と
 * 「ファイル」（システムのファイル選択）を案内する（[NoMediaAccess]）。以前は許可されないと
 * この画面自体を開かず、ファイル選択の入口（右上の「ファイル」）まで辿り着けなかったので、
 * 許可しなかった人は動画を1本も追加できなかった。
 *
 * @param reloadToken 値が変わると一覧を取り直す。許可を聞き直した直後に使う
 * @param onRequestAccess 許可を聞き直す（「選択した項目のみ」の選び直しも、これで出る）
 */
@Composable
fun GalleryPickerDialog(
    reloadToken: Int,
    onDismiss: () -> Unit,
    onPick: (List<Uri>) -> Unit,
    onUseFilePicker: () -> Unit,
    onRequestAccess: () -> Unit
) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<GalleryVideo>?>(null) }
    // 選択中のURI。追加後の並びは撮影日時順で、選んだ順とは無関係なので、順番は持たない
    val selected = remember { mutableStateSetOf<Uri>() }
    // 設定アプリで許可を変えて戻ってきたとき、許可の状態を読み直すための合図。
    // 2回断るとシステムは許可の画面をもう出さないので、設定から許可されることがある
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }
    val hasAccess = remember(reloadToken, resumeCount) { hasMediaAccess(context) }
    val isPartial = remember(reloadToken, resumeCount) { hasPartialMediaAccess(context) }
    val thumbnails = remember { ThumbnailCache() }

    // 許可が変わったときも取り直す（設定から許可されて戻ってきたときなど）
    LaunchedEffect(reloadToken, hasAccess) {
        if (!hasAccess) {
            videos = emptyList()
            selected.clear()
            return@LaunchedEffect
        }
        videos = null
        val loaded = queryGalleryVideos(context)
        videos = loaded

        // 許可する動画を選び直すと一覧が入れ替わる。一覧から消えた動画の選択が
        // 残ったままだと、見えないのに追加されてしまうので、残るものだけにする。
        selected.retainAll(loaded.mapTo(HashSet()) { it.uri })
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            // 画面端からの余白（8dp）と内側のコンテンツ余白（12dp、他のCard類と同じ値）を
            // 分けて、同じ値の二重適用に見えないようにしている
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                GalleryPickerHeader(onUseFilePicker = onUseFilePicker)

                // 一部の動画だけ許可している場合は、対象を選び直せるようにする
                if (isPartial) {
                    PartialAccessBanner(onChangeSelection = onRequestAccess)
                }

                if (hasAccess) {
                    VideoGrid(
                        videos = videos,
                        selected = selected,
                        thumbnails = thumbnails,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else {
                    NoMediaAccess(
                        onRequestAccess = onRequestAccess,
                        onOpenSettings = { openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                GalleryPickerFooter(
                    selectedCount = selected.size,
                    onDismiss = onDismiss,
                    onPick = { onPick(oldestFirst(videos.orEmpty(), selected)) }
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
        ) { Text("ファイル") }
    }
}

/**
 * 動画へのアクセスが許可されていないときに、一覧の代わりに出す案内。
 *
 * 「許可する」は許可の画面をもう一度出す。ただし2回断るとシステムはもう画面を出さず、
 * 押しても何も起きないので、設定を開く道も並べる。「ファイル」からなら許可が無くても選べる
 * （選んだ動画にだけ、その場で読む権限がもらえる）ことも伝える。
 */
@Composable
internal fun NoMediaAccess(
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "動画へのアクセスが許可されていないため、一覧を出せません",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "右上の「ファイル」から選んで追加することもできます",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRequestAccess) { Text("許可する") }
            OutlinedButton(onClick = onOpenSettings) { Text("設定を開く") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "「許可する」で何も出ないときは、設定の「権限」から「写真と動画」を許可してください",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** このアプリの設定画面（権限を変えられる）を開く */
private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
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
        ) { Text("選択を変更") }
    }
}

@Composable
private fun VideoGrid(
    videos: List<GalleryVideo>?,
    selected: SnapshotStateSet<Uri>,
    thumbnails: ThumbnailCache,
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
                    VideoTile(
                        video = video,
                        thumbnails = thumbnails,
                        isSelected = video.uri in selected,
                        onClick = { toggleSelection(selected, video.uri) }
                    )
                }
            }
        }
    }
}

/** 選択の追加・解除 */
private fun toggleSelection(selected: MutableSet<Uri>, uri: Uri) {
    if (!selected.remove(uri)) selected.add(uri)
}

/**
 * 選択中の動画を、追加処理へ渡す順に並べる。一覧は追加日時の新しい順なので、その逆（古い順）。
 *
 * 追加後の並びは撮影日時順で、この順は関係しない。撮影時刻がどこからも取れない動画だけが、
 * この順に代用時刻を割り当てられる（[com.example.myvlogapp.VlogViewModel.addClips]）。
 */
internal fun oldestFirst(videos: List<GalleryVideo>, selected: Set<Uri>): List<Uri> =
    videos.filter { it.uri in selected }.reversed().map { it.uri }

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

/** 選択中はチェックマークと枠線で示す（追加後の並びは撮影日時順なので、番号は出さない） */
@Composable
private fun VideoTile(
    video: GalleryVideo,
    thumbnails: ThumbnailCache,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val thumbnail = rememberThumbnail(video.uri, thumbnails)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(TILE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, TILE_SHAPE)
                } else {
                    Modifier
                }
            )
            // 選択状態はselectableが読み上げに伝える（「選択済み」など、端末の言語で読まれる）。
            // 説明文にも「選択中」を足すと、同じ状態が2回続けて読まれる
            .selectable(selected = isSelected, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = video.name
            }
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
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

        if (isSelected) {
            Icon(
                VlogIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(3.dp)
                    .size(14.dp)
            )
        }
    }
}
