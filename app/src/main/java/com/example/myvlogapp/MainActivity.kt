package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Rect as AndroidRect
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.myvlogapp.ui.theme.DarkOnSplitMarker
import com.example.myvlogapp.ui.theme.DarkSplitMarker
import com.example.myvlogapp.ui.theme.LightOnSplitMarker
import com.example.myvlogapp.ui.theme.LightSplitMarker
import com.example.myvlogapp.ui.theme.MyVlogAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            // ライト／ダークは端末の設定に自動追従する（MyVlogAppTheme の既定引数）
            MyVlogAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VlogAppScreen()
                }
            }
        }
    }
}

/**
 * 画面構成は縦横で切り替える。
 *
 * 縦長（通常のスマホ / Foldの外側画面）: 1カラム
 *   プレビュー 50% → ボタン → タイムライン 30% → ひとこと 20%
 *
 * 横長（Foldの展開時 / タブレット / 横向き）: 2ペイン
 *   左にプレビューとボタン、右にタイムラインとひとこと。
 *   1カラムのまま縦に4分割すると、縦が短い端末でタイムラインが潰れて
 *   スライダーや文字が入り切らなくなるため。
 */
@Composable
fun VlogAppScreen(viewModel: VlogViewModel = viewModel()) {
    val context = LocalContext.current
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val selectedClip = clips.getOrNull(selectedIndex)
    val isExporting = exportState is ExportState.Running

    // キーボードが出ている間は、ひとこと入力欄に画面を大きく割り当てる。
    // safeDrawingPaddingがIMEのぶんだけ表示領域を詰めるため、比率が固定のままだと
    // セクションが一様に潰れて入力欄が読めなくなってしまう。
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // 波形を足したぶんタイムラインの取り分を増やしてある。
    // ここを削るとトリミングのスライダーがカードの下端で切れ、
    // 一度スクロールしないと尺を変えられなくなる。
    val previewWeight = if (imeVisible) 0.25f else 0.42f
    val timelineWeight = if (imeVisible) 0.20f else 0.40f
    val editorWeight = if (imeVisible) 0.55f else 0.18f

    // プレビューにも書き出しと同じフォントを使う。
    // 既定フォントのままだと、サイズを合わせても書き出し結果と別物に見えてしまう。
    val hitokotoFontFamily = remember(context) {
        FontFamily(Font(path = "fonts/$TITLE_FONT_ASSET", assetManager = context.assets))
    }
    val timeFontFamily = remember(context) {
        FontFamily(Font(path = "fonts/$TIME_FONT_ASSET", assetManager = context.assets))
    }

    // 動画の取り込みは自前のギャラリー画面（MediaStore）が主。
    // システムのフォトピッカーを使わないのは、返るURIがプロセスの生存中しか有効でなく、
    // アプリを閉じると編集の続きを復元できないため。
    // SAFのファイル選択は、MediaStoreに出てこない場所（Downloadなど）用に残してある。
    var showGallery by remember { mutableStateOf(false) }
    // 一時保存の一覧（保存と読み出しを1枚のダイアログでまかなう）
    var showSaves by remember { mutableStateOf(false) }
    // 許可する動画を選び直したときに一覧を取り直すための合図
    var galleryReloadToken by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Android 14の「選択した項目のみ許可」だと READ_MEDIA_VIDEO は拒否のまま
        // 別の権限が許可されるので、どれか1つでも通れば一覧を開く
        if (results.values.any { it }) {
            showGallery = true
            galleryReloadToken++
        } else {
            Toast.makeText(
                context,
                "動画へのアクセスが許可されていません。「ファイルから選ぶ」もご利用いただけます",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val openGallery = {
        if (hasMediaAccess(context)) showGallery = true
        else permissionLauncher.launch(mediaPermissions)
    }

    val filePicker = rememberLauncherForActivityResult(
        remember { OpenVideosFromCamera() }
    ) { uris: List<Uri> ->
        // 次にアプリを開いたときも読めるよう、永続的な読み取り権限をもらっておく。
        // これが取れたURIだけが復元対象になる（動画自体はコピーしない）。
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        viewModel.addClips(uris)
    }

    if (showGallery) {
        GalleryPickerDialog(
            reloadToken = galleryReloadToken,
            onDismiss = { showGallery = false },
            onPick = { uris ->
                showGallery = false
                viewModel.addClips(uris)
            },
            onUseFilePicker = {
                showGallery = false
                filePicker.launch(arrayOf("video/*"))
            },
            // 権限を再リクエストすると、システムの「動画を選択」画面が再表示される
            onChangeSelection = { permissionLauncher.launch(mediaPermissions) }
        )
    }

    if (showSaves) {
        val projects by viewModel.projects.collectAsStateWithLifecycle()
        // 開くたびに読み直す。保存・削除のたびにViewModel側でも更新される
        LaunchedEffect(Unit) { viewModel.refreshProjects() }

        SaveLoadDialog(
            projects = projects,
            canSave = clips.isNotEmpty() && !isExporting,
            onSave = viewModel::saveProject,
            onLoad = { id ->
                showSaves = false
                viewModel.loadProject(id)
            },
            onDelete = viewModel::deleteProject,
            onDismiss = { showSaves = false }
        )
    }

    // Toastなどの一過性イベント
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is VlogEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 戻るボタンでActivityが終了するとViewModelごと破棄され、読み込んだ動画が消える。
    // クリップを読み込んでいる間は、ホームボタンと同じ「バックグラウンドへ回す」動きにして、
    // 戻ってきたときに作業を続けられるようにする。
    val activity = context as? Activity
    BackHandler(enabled = clips.isNotEmpty()) {
        activity?.moveTaskToBack(true)
    }

    // アプリが背面に回ったら再生を止める
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 再生位置の更新とトリミング範囲の連続再生。
    // キーをUnitにしているのは、selectedIndexだとクリップが切り替わるたびに
    // ループが作り直されて監視が途切れてしまうため。
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            viewModel.tick()
        }
    }

    // 縦横の判定にはConfigurationの画面サイズを使う。
    // BoxWithConstraintsの実測値はキーボードのぶん縮むため、そちらで判定すると
    // Foldの展開時（ほぼ正方形）にキーボードを出した瞬間へ縦→横と判定が裏返り、
    // レイアウトごと作り直されて入力欄のフォーカスが飛んでしまう。
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp > configuration.screenHeightDp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        if (isWide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── 左ペイン：プレビューと操作ボタン ──────────────────
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PreviewSection(
                        selectedClip = selectedClip,
                        viewModel = viewModel,
                        hitokotoFontFamily = hitokotoFontFamily,
                        timeFontFamily = timeFontFamily,
                        exportState = exportState,
                        isExporting = isExporting,
                        canExport = clips.isNotEmpty(),
                        // 横並びのときはプレビューが左ペインを丸ごと使える
                        previewWeight = 1f,
                        onAdd = openGallery,
                        onOpenSaves = { showSaves = true }
                    )
                }

                // ── 右ペイン：タイムラインとひとこと ──────────────────
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    EditSection(
                        viewModel = viewModel,
                        clips = clips,
                        selectedIndex = selectedIndex,
                        selectedClip = selectedClip,
                        isExporting = isExporting,
                        timelineWeight = timelineWeight,
                        editorWeight = editorWeight
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PreviewSection(
                    selectedClip = selectedClip,
                    viewModel = viewModel,
                    hitokotoFontFamily = hitokotoFontFamily,
                    timeFontFamily = timeFontFamily,
                    exportState = exportState,
                    isExporting = isExporting,
                    canExport = clips.isNotEmpty(),
                    previewWeight = previewWeight,
                    onAdd = openGallery,
                    onOpenSaves = { showSaves = true }
                )
                Spacer(Modifier.height(12.dp))
                EditSection(
                    viewModel = viewModel,
                    clips = clips,
                    selectedIndex = selectedIndex,
                    selectedClip = selectedClip,
                    isExporting = isExporting,
                    timelineWeight = timelineWeight,
                    editorWeight = editorWeight
                )
            }
        }
    }
}

/**
 * プレビュー・操作ボタン・進捗のまとまり。
 *
 * 縦1カラムと横2ペインで中身は同じなので、それぞれの分岐に書き写さず1箇所にまとめる。
 * 分けて書いていると、片方だけ直して縦と横で挙動が食い違う事故が起きる。
 * 違うのは「プレビューに何割を割り当てるか」だけなので、そこだけ引数で受ける。
 */
@Composable
private fun ColumnScope.PreviewSection(
    selectedClip: VlogClip?,
    viewModel: VlogViewModel,
    hitokotoFontFamily: FontFamily,
    timeFontFamily: FontFamily,
    exportState: ExportState,
    isExporting: Boolean,
    canExport: Boolean,
    previewWeight: Float,
    onAdd: () -> Unit,
    onOpenSaves: () -> Unit
) {
    // ひとことはクリップの途中で切り替わるので、再生位置を見て出し分ける
    val positionMs by viewModel.playbackPositionMs.collectAsStateWithLifecycle()

    // 書き出し中はフォアグラウンドサービスの通知を出す（VlogExportService）。
    // Android 13以降は表示に実行時許可が要るため、書き出し開始前にリクエストする。
    // 拒否されても書き出し自体は行われる（通知が出ないだけ）。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒否されても書き出しは続行するので結果は無視してよい */ }

    PreviewPane(
        selectedClip = selectedClip,
        positionMs = positionMs,
        player = viewModel.player,
        hitokotoFontFamily = hitokotoFontFamily,
        timeFontFamily = timeFontFamily,
        modifier = Modifier.fillMaxWidth().weight(previewWeight)
    )
    Spacer(Modifier.height(12.dp))
    ActionButtons(
        isExporting = isExporting,
        canExport = canExport,
        onAdd = onAdd,
        onOpenSaves = onOpenSaves,
        onExport = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.export()
        },
        onCancel = viewModel::cancelExport
    )
    ExportProgress(exportState)
}

/** タイムラインとひとこと入力のまとまり。分けない理由は [PreviewSection] と同じ */
@Composable
private fun ColumnScope.EditSection(
    viewModel: VlogViewModel,
    clips: List<VlogClip>,
    selectedIndex: Int,
    selectedClip: VlogClip?,
    isExporting: Boolean,
    timelineWeight: Float,
    editorWeight: Float
) {
    val positionMs by viewModel.playbackPositionMs.collectAsStateWithLifecycle()

    TimelinePane(
        viewModel = viewModel,
        clips = clips,
        selectedIndex = selectedIndex,
        isExporting = isExporting,
        modifier = Modifier.fillMaxWidth().weight(timelineWeight)
    )
    Spacer(Modifier.height(12.dp))
    EditorPane(
        selectedClip = selectedClip,
        positionMs = positionMs,
        isExporting = isExporting,
        onTextChange = viewModel::updateText,
        modifier = Modifier.fillMaxWidth().weight(editorWeight)
    )
}

/**
 * プレビュー。出力と同じ 1920:1080 のキャンバス比率で表示する。
 *
 * キャンバスの実寸から文字サイズを換算するので、どの端末・どのペイン幅でも
 * 書き出し結果と同じ見た目になる。
 *
 * 黒い下地を敷くのは、外枠ではなくキャンバスそのもの。
 * 外枠に塗ると、縦画面のように枠がキャンバスより背の高いときに上下へ黒帯が伸び、
 * 「どこまでが動画になる範囲か」が見た目から分からなくなる。
 */
@OptIn(UnstableApi::class)
@Composable
private fun PreviewPane(
    selectedClip: VlogClip?,
    positionMs: Long,
    player: ExoPlayer,
    hitokotoFontFamily: FontFamily,
    timeFontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    if (selectedClip == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "「動画を追加」から動画を選んでください",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 幅・高さのどちらが効いても収まるように、キャンバスの寸法を自分で出す。
        // aspectRatioだけに任せると、横長のペインでは高さがはみ出して切れてしまう。
        val canvasRatio = CANVAS_WIDTH.toFloat() / CANVAS_HEIGHT
        val canvasWidth = minOf(maxWidth, maxHeight * canvasRatio)
        val canvasHeight = canvasWidth / canvasRatio

        val density = LocalDensity.current
        // toSp()を使うのがポイント。端末の「文字サイズ」設定に左右されず、
        // 常に書き出し結果と同じ物理サイズで表示される。
        val canvasScale = with(density) { canvasHeight.toPx() } /
                CANVAS_HEIGHT * PREVIEW_FONT_SCALE
        val hitokotoSize = with(density) { (HITOKOTO_FONT_PT * canvasScale).toSp() }
        val timeSize = with(density) { (TIME_FONT_PT * canvasScale).toSp() }
        val timeMargin = with(density) { (TIME_MARGIN_PT * canvasScale).toDp() }

        Box(
            modifier = Modifier
                .size(canvasWidth, canvasHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // ここから下は書き出しに焼き込まれる文字。配色はテーマに追従させず、
            // 出力と同じ白のままにしておく。
            Text(
                text = selectedClip.textAt(positionMs),
                color = Color.White,
                fontSize = hitokotoSize,
                fontFamily = hitokotoFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
            Text(
                text = selectedClip.timeText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = timeSize,
                fontFamily = timeFontFamily,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = timeMargin)
            )
        }
    }
}

@Composable
private fun ActionButtons(
    isExporting: Boolean,
    canExport: Boolean,
    onAdd: () -> Unit,
    onOpenSaves: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左右のボタンは既定の余白(24dp)だと、真ん中にアイコンを挟んだ幅では
        // 「動画を追加」が2行に折り返してしまうので詰めてある
        val labelPadding = PaddingValues(horizontal = 12.dp)

        // 主役は「書き出し」なので、こちらは一段控えめなトーナルボタンにする
        FilledTonalButton(
            onClick = onAdd,
            enabled = !isExporting,
            contentPadding = labelPadding,
            modifier = Modifier.weight(1f)
        ) { Text("動画を追加", maxLines = 1) }

        // 一時保存。文字を置くと左右のボタンの取り分が減るのでアイコンだけにする
        FilledTonalIconButton(
            onClick = onOpenSaves,
            enabled = !isExporting
        ) {
            Icon(
                VlogIcons.File,
                contentDescription = "編集内容の保存と読み出し",
                modifier = Modifier.size(20.dp)
            )
        }

        if (isExporting) {
            OutlinedButton(
                onClick = onCancel,
                contentPadding = labelPadding,
                modifier = Modifier.weight(1f)
            ) { Text("中止", maxLines = 1) }
        } else {
            Button(
                onClick = onExport,
                enabled = canExport,
                contentPadding = labelPadding,
                modifier = Modifier.weight(1f)
            ) { Text("書き出し", maxLines = 1) }
        }
    }
}

@Composable
private fun ExportProgress(exportState: ExportState) {
    (exportState as? ExportState.Running)?.let { state ->
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * タイムライン。
 *
 * 中身を縦スクロールにしてあるのは、画面が短いとき（Foldの展開時や横向き）に
 * スライダーや説明文が切れて操作できなくなるのを防ぐため。
 */
@Composable
private fun TimelinePane(
    viewModel: VlogViewModel,
    clips: List<VlogClip>,
    selectedIndex: Int,
    isExporting: Boolean,
    modifier: Modifier = Modifier
) {
    val selectedClip = clips.getOrNull(selectedIndex)
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val waveforms by viewModel.waveforms.collectAsStateWithLifecycle()
    val positionMs by viewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val autoAdvance by viewModel.autoAdvance.collectAsStateWithLifecycle()

    // 波形は選択中のクリップだけ用意する。全件を先読みするとデコードが渋滞して、
    // 肝心の「いま触っているクリップ」の表示が後回しになる。
    LaunchedEffect(selectedClip?.uri) {
        selectedClip?.let { viewModel.requestWaveform(it) }
    }

    // すべて削除は取り返しがつかないので確認を挟む（取り消しは「もとに戻す」でもできる）
    var confirmRemoveAll by remember { mutableStateOf(false) }

    if (confirmRemoveAll) {
        RemoveAllDialog(
            onDismiss = { confirmRemoveAll = false },
            onConfirm = {
                confirmRemoveAll = false
                viewModel.removeAll()
            }
        )
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 見出しと操作バーを2行に分ける。
            // 1行に収めていた頃は、ボタンが8個になった時点で横幅の狭い端末では
            // 見出しが「タ…」まで潰れ、それでもボタンが画面外へはみ出していた。
            Text(
                "タイムライン",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val enabled = selectedClip != null && !isExporting

                // 並びは 連続再生 → 入れ替え → もとに戻す → やり直す
                //        → ひとことを分割 → 削除 → すべて削除
                TimelineToggleButton(
                    icon = VlogIcons.Play,
                    checked = autoAdvance,
                    contentDescription = if (autoAdvance) {
                        "連続再生：オン（終わったら次のクリップへ進みます）"
                    } else {
                        "連続再生：オフ（クリップの終わりで止まります）"
                    },
                    enabled = clips.isNotEmpty() && !isExporting,
                    onClick = { viewModel.setAutoAdvance(!autoAdvance) }
                )

                TimelineDivider()

                TimelineIconButton(
                    icon = VlogIcons.MoveLeft,
                    contentDescription = "ひとつ前へ移動",
                    enabled = enabled && selectedIndex > 0,
                    onClick = { viewModel.moveSelected(-1) }
                )
                TimelineIconButton(
                    icon = VlogIcons.MoveRight,
                    contentDescription = "ひとつ後ろへ移動",
                    enabled = enabled && selectedIndex < clips.lastIndex,
                    onClick = { viewModel.moveSelected(1) }
                )

                TimelineDivider()

                TimelineIconButton(
                    icon = VlogIcons.Undo,
                    contentDescription = "もとに戻す",
                    enabled = canUndo && !isExporting,
                    onClick = viewModel::undo
                )
                TimelineIconButton(
                    icon = VlogIcons.Redo,
                    contentDescription = "やり直す",
                    enabled = canRedo && !isExporting,
                    onClick = viewModel::redo
                )

                TimelineDivider()

                // 再生ヘッドが区切りの上にあるときは、同じボタンが解除に変わる。
                // 区切りを消す手段が「もとに戻す」しか無いと、あとから直せなくなるため。
                val splitOnPlayhead = selectedClip?.splitPointNear(positionMs)
                TimelineIconButton(
                    icon = if (splitOnPlayhead == null) VlogIcons.SplitText
                    else VlogIcons.SplitTextOff,
                    contentDescription = if (splitOnPlayhead == null) {
                        "ここでひとことを分割（動画は切りません）"
                    } else {
                        "この区切りを解除"
                    },
                    enabled = enabled,
                    onClick = {
                        if (splitOnPlayhead == null) viewModel.splitTextAtPlayhead()
                        else viewModel.removeSplit(splitOnPlayhead)
                    },
                    tint = splitMarkerColor()
                )

                TimelineDivider()

                // 押し間違えても「もとに戻す」で復帰できるので、1件の削除は確認なしで消す
                TimelineIconButton(
                    icon = VlogIcons.Delete,
                    contentDescription = "選択中のクリップを削除",
                    enabled = enabled,
                    onClick = viewModel::removeSelected,
                    tint = MaterialTheme.colorScheme.error
                )
                TimelineIconButton(
                    icon = VlogIcons.DeleteSweep,
                    contentDescription = "すべて削除",
                    enabled = clips.isNotEmpty() && !isExporting,
                    onClick = { confirmRemoveAll = true },
                    tint = MaterialTheme.colorScheme.error
                )
            }

            if (clips.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "動画を追加するとここに並びます",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(clips, key = { _, clip -> clip.id }) { index, clip ->
                        val isSelected = index == selectedIndex
                        Surface(
                            onClick = { viewModel.select(index) },
                            // 高さは中身に任せる。固定にすると端末の文字サイズ設定を
                            // 上げたときに尺の行がタイルからはみ出して切れる
                            modifier = Modifier.width(104.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            // 未選択にも枠を付ける。カードと明度が近く、無地だと
                            // どこまでが1クリップなのか輪郭が見えないため
                            border = if (isSelected)
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    clip.timeText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                // 途中で切り替わる場合も、タイルには頭に出る文字を載せる
                                Text(
                                    clip.textAt(clip.startMs).ifBlank { DEFAULT_HITOKOTO },
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // 時刻を見出しに上げたぶん、ここは尺の表示に使う
                                    Text(
                                        formatSeconds(clip.trimmedDurationMs),
                                        fontSize = 10.sp
                                    )
                                    // ひとことを分割してあるクリップは、区間の数を出す。
                                    // タイルを見ただけで「途中で文字が変わる」と分かる
                                    if (clip.texts.size > 1) {
                                        Text(
                                            "1-${clip.texts.size}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = onSplitMarkerColor(),
                                            modifier = Modifier
                                                .background(
                                                    splitMarkerColor(),
                                                    RoundedCornerShape(50)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                selectedClip?.let { clip ->
                    if (clip.durationMs > 0) {
                        Text(
                            text = "${clip.timeText}：" +
                                    "${formatSeconds(clip.startMs)} 〜 ${formatSeconds(clip.endMs)}" +
                                    "（${formatSeconds(clip.trimmedDurationMs)}）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val key = clip.uri.toString()
                        WaveformTrimmer(
                            waveform = waveforms[key],
                            isLoading = !waveforms.containsKey(key),
                            texts = clip.texts,
                            durationMs = clip.durationMs,
                            startMs = clip.startMs,
                            endMs = clip.endMs,
                            positionMs = positionMs,
                            enabled = !isExporting,
                            onTrimChange = viewModel::updateTrim,
                            onTrimMove = viewModel::moveTrim,
                            onSplitMove = viewModel::moveSplit,
                            onSeek = viewModel::seekWithinTrim,
                            onScrubStart = viewModel::beginScrub,
                            onScrubEnd = viewModel::endScrub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(WAVEFORM_HEIGHT)
                                .padding(top = 8.dp)
                        )

                        Text(
                            if (clip.texts.size > 1) {
                                "紫のラインがひとことの区切り・つまんで移動、区間内は長押しで範囲ごと移動"
                            } else {
                                "波形の端をつまんで長さを調整・内側は長押しで範囲ごと移動"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        Text(
                            "この動画は長さを取得できませんでした",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * 操作バーのボタン1個の大きさ。
 * Materialの推奨は48dpだが、8個並べると横幅の狭い端末で行からはみ出してしまう。
 * アイコン自体は20dpあるので、密なツールバーとしては触れる範囲に収まっている。
 */
private val TOOLBAR_BUTTON_SIZE = 32.dp

/** 波形の高さ。つまみを指で掴める大きさが要るので、表示だけだった頃より厚くしてある */
private val WAVEFORM_HEIGHT = 76.dp

/** トリミングつまみの幅 */
private val TRIM_HANDLE_WIDTH = 11.dp

/** つまみを掴んだと判定する距離。指の腹の太さを見込んで広めに取る */
private val TRIM_GRAB_RADIUS = 30.dp

/** これ以上は詰められない長さ。0にできてしまうと書き出しが通らなくなる */
private const val MIN_TRIM_MS = 300L

private enum class TrimHandle { Start, End }

/**
 * 波形そのものがトリミングバーを兼ねる。
 *
 * 別途スライダーを置いていた頃は、波形で位置を読んでから下のスライダーへ視線と指を
 * 移す必要があった。掴む場所と見る場所が同じなら、その往復が要らない。
 *
 * - 左右の端のつまみをドラッグ → 開始・終了位置を変更（掴んだ側の映像がプレビューに出る）
 * - 内側をなぞる / タップ → その位置へ頭出し（シークバーを兼ねる）
 *
 * つまみを掴んだかどうかは指を置いた瞬間に決まり、以後そのまま最後まで同じ役割で動く。
 * 途中で判定が切り替わると、トリミングのつもりが再生位置だけ動いた、という事故になる。
 *
 * つまみが画面端で切れないよう、トラックはつまみの半分ぶん内側に取ってある。
 */
@Composable
private fun WaveformTrimmer(
    waveform: Waveform?,
    isLoading: Boolean,
    texts: List<TextSegment>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    enabled: Boolean,
    onTrimChange: (Long, Long, Long) -> Unit,
    onTrimMove: (Long, Long) -> Unit,
    onSplitMove: (Int, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val handleColor = MaterialTheme.colorScheme.primary
    val gripColor = MaterialTheme.colorScheme.onPrimary
    val playheadColor = MaterialTheme.colorScheme.tertiary
    val splitColor = splitMarkerColor()
    val splitLabelColor = onSplitMarkerColor()

    // 区切りの番号を描くのに使う。Canvasの中では作れないのでここで用意しておく
    val textMeasurer = rememberTextMeasurer()

    // ドラッグ中のつまみ・分割ライン。掴んでいる側を一回り大きく／太く描いて、
    // どれを動かしているのかを指の下からでも分かるようにする
    var activeHandle by remember { mutableStateOf<TrimHandle?>(null) }
    var activeSplitIndex by remember { mutableStateOf<Int?>(null) }
    var isMovingTrim by remember { mutableStateOf(false) }

    // pointerInputのラムダは長く生き続けるので、最新値はrememberUpdatedState経由で読む。
    // 直接キャプチャすると、ドラッグ中ずっと掴んだ瞬間の値を見続けてしまう。
    val latestStart by rememberUpdatedState(startMs)
    val latestEnd by rememberUpdatedState(endMs)
    val latestDuration by rememberUpdatedState(durationMs)
    val latestTexts by rememberUpdatedState(texts)
    val latestTrimChange by rememberUpdatedState(onTrimChange)
    val latestTrimMove by rememberUpdatedState(onTrimMove)
    val latestSplitMove by rememberUpdatedState(onSplitMove)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestScrubStart by rememberUpdatedState(onScrubStart)
    val latestScrubEnd by rememberUpdatedState(onScrubEnd)

    val density = LocalDensity.current
    val handleHalfPx = with(density) { TRIM_HANDLE_WIDTH.toPx() / 2f }
    val grabRadiusPx = with(density) { TRIM_GRAB_RADIUS.toPx() }
    val haptics = LocalHapticFeedback.current

    // 左右どちらかのつまみが画面のヘリに近いと、掴んだつもりがOSの「戻る」スワイプに
    // 奪われる端末がある。波形トリマー全体（左端〜右端）をジェスチャー除外領域として
    // 申告し、この範囲では常に自前のタッチ処理を優先させる。選択中クリップが変わって
    // 表示が消えるときは除外を解除しないと、別の場所にまで戻るジェスチャーが効かなくなる。
    //
    // systemGestureExclusionRects はAPI 29以降にしか無い。minSdkは24なので、
    // 直に呼ぶと Android 9 以下で NoSuchMethodError で落ちる。
    // ジェスチャーナビゲーション自体がAPI 29からの機能なので、それ未満では何もしない。
    val view = LocalView.current
    val supportsGestureExclusion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    DisposableEffect(view, supportsGestureExclusion) {
        onDispose {
            if (supportsGestureExclusion) view.systemGestureExclusionRects = emptyList()
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                if (!supportsGestureExclusion) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                view.systemGestureExclusionRects = listOf(
                    AndroidRect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt()
                    )
                )
            }
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    // 掴んでいる最中に指以外の理由でジェスチャーが打ち切られることがある
                    // （親の縦スクロールに主導権を奪われる、書き出し開始で enabled が
                    // 変わって pointerInput が作り直される、クリップの選択が変わって
                    // このコンポーザブルごと消えるなど）。
                    // その場合ここのコルーチンはキャンセルされて以降の行が実行されないため、
                    // 強調表示のフラグとスクラブ終了通知は必ず finally で戻す。
                    // 戻し忘れると、つまみが太ったまま固まる／指を離しても再生が再開しない、
                    // といった状態が画面に残り続ける。
                    var scrubbing = false
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val track = trackMetrics(size.width.toFloat(), handleHalfPx)
                        val startX = track.msToX(latestStart, latestDuration)
                        val endX = track.msToX(latestEnd, latestDuration)

                        // つまみと分割ラインのうち、いちばん近いものを探す。
                        // どちらの許容範囲にも入らなければ「本体」として扱う
                        val toStart = kotlin.math.abs(down.position.x - startX)
                        val toEnd = kotlin.math.abs(down.position.x - endX)
                        val handleKind = if (toStart <= toEnd) TrimHandle.Start else TrimHandle.End
                        val handleDist = minOf(toStart, toEnd)

                        var nearestSplit: Int? = null
                        var nearestSplitDist = Float.MAX_VALUE
                        for (i in 1 until latestTexts.size) {
                            val splitX = track.msToX(latestTexts[i].startMs, latestDuration)
                            val dist = kotlin.math.abs(down.position.x - splitX)
                            if (dist < nearestSplitDist) {
                                nearestSplitDist = dist
                                nearestSplit = i
                            }
                        }

                        val useHandle = handleDist <= grabRadiusPx && handleDist <= nearestSplitDist
                        val useSplit =
                            !useHandle && nearestSplit != null && nearestSplitDist <= grabRadiusPx

                        when {
                            // --- 端のつまみ：即ドラッグで伸縮 ---
                            useHandle -> {
                                activeHandle = handleKind
                                val grabOffset =
                                    if (handleKind == TrimHandle.Start) down.position.x - startX
                                    else down.position.x - endX

                                dragUntilRelease(down.id) { change ->
                                    val ms =
                                        track.xToMs(change.position.x - grabOffset, latestDuration)
                                    when (handleKind) {
                                        TrimHandle.Start -> {
                                            val next = ms.coerceIn(0L, latestEnd - MIN_TRIM_MS)
                                            latestTrimChange(next, latestEnd, next)
                                        }
                                        TrimHandle.End -> {
                                            val next = ms.coerceIn(
                                                latestStart + MIN_TRIM_MS, latestDuration
                                            )
                                            latestTrimChange(latestStart, next, next)
                                        }
                                    }
                                }
                            }

                            // --- 分割ライン：即ドラッグで移動 ---
                            useSplit -> {
                                val index = nearestSplit!!
                                activeSplitIndex = index
                                val splitX =
                                    track.msToX(latestTexts[index].startMs, latestDuration)
                                val grabOffset = down.position.x - splitX

                                dragUntilRelease(down.id) { change ->
                                    val ms =
                                        track.xToMs(change.position.x - grabOffset, latestDuration)
                                    latestSplitMove(index, ms)
                                }
                            }

                            // --- 本体：すぐ動かせば従来通りなぞって頭出し、
                            //     長押ししてから動かせば区間ごと移動 ---
                            else -> {
                                val outcome =
                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        awaitSlopOrRelease(
                                            down.id, viewConfiguration.touchSlop, down.position
                                        )
                                    }

                                when (outcome) {
                                    is DragOutcome.Dragged -> {
                                        // すぐ動いた＝なぞって頭出し（従来のシーク）
                                        scrubbing = true
                                        latestScrubStart()
                                        latestSeek(
                                            track.xToMs(
                                                outcome.change.position.x, latestDuration
                                            )
                                        )
                                        dragUntilRelease(outcome.change.id) { change ->
                                            latestSeek(
                                                track.xToMs(change.position.x, latestDuration)
                                            )
                                        }
                                    }

                                    DragOutcome.Released -> {
                                        // 動かさず離した＝タップ。その場へ頭出し
                                        latestSeek(track.xToMs(down.position.x, latestDuration))
                                    }

                                    null -> {
                                        // 動かさず一定時間経過＝長押し。
                                        // まだ指が乗っていれば区間ごと移動へ切り替える
                                        val stillDown = currentEvent.changes
                                            .firstOrNull { it.id == down.id }?.pressed == true
                                        if (!stillDown) {
                                            latestSeek(
                                                track.xToMs(down.position.x, latestDuration)
                                            )
                                        } else {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            isMovingTrim = true
                                            val originalStart = latestStart
                                            val pxPerMs =
                                                track.width / latestDuration.coerceAtLeast(1L)
                                            val anchorX = down.position.x

                                            dragUntilRelease(down.id) { change ->
                                                val deltaMs =
                                                    ((change.position.x - anchorX) / pxPerMs)
                                                        .toLong()
                                                val targetStart = originalStart + deltaMs
                                                latestTrimMove(targetStart, targetStart)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        activeHandle = null
                        activeSplitIndex = null
                        isMovingTrim = false
                        if (scrubbing) latestScrubEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas
            val track = trackMetrics(size.width, handleHalfPx)
            val startX = track.msToX(startMs, durationMs)
            val endX = track.msToX(endMs, durationMs)
            val centerY = size.height / 2f

            // --- 波形 ---
            val amplitudes = waveform?.takeIf { it.hasAudio }?.amplitudes
            if (amplitudes != null && amplitudes.isNotEmpty()) {
                val slot = track.width / amplitudes.size
                val barWidth = (slot * 0.68f).coerceAtLeast(1f)
                val minHalf = 0.75.dp.toPx()
                val maxHalf = (size.height / 2f - 10.dp.toPx()).coerceAtLeast(minHalf)

                amplitudes.forEachIndexed { index, amplitude ->
                    val left = track.left + index * slot + (slot - barWidth) / 2f
                    val half = (amplitude * maxHalf).coerceAtLeast(minHalf)
                    val center = left + barWidth / 2f
                    drawRoundRect(
                        color = if (center in startX..endX) activeColor else inactiveColor,
                        topLeft = Offset(left, centerY - half),
                        size = Size(barWidth, half * 2f),
                        cornerRadius = CornerRadius(barWidth / 2f)
                    )
                }
            } else {
                // 読み込み中や無音でも、つまめる範囲が分かるよう土台の線だけ引く
                drawLine(
                    inactiveColor,
                    Offset(track.left, centerY),
                    Offset(track.right, centerY),
                    1.dp.toPx()
                )
            }

            // --- 選択範囲の枠。上下の桟でトリム区間を囲う。
            //     長押しで区間ごと移動している間は太くして、動かしていることを示す ---
            val railHeight = if (isMovingTrim) 5.dp.toPx() else 3.dp.toPx()
            drawRect(
                color = activeColor,
                topLeft = Offset(startX, 0f),
                size = Size((endX - startX).coerceAtLeast(0f), railHeight)
            )
            drawRect(
                color = activeColor,
                topLeft = Offset(startX, size.height - railHeight),
                size = Size((endX - startX).coerceAtLeast(0f), railHeight)
            )

            // --- ひとことの区切り ---
            // 動画は切っていないので、切れ目は自分で描かないと分からない。
            // 波形（primary）と同系だが一段濃い紫にして、線と番号を同じ色で揃える。
            if (texts.size > 1) {
                // トリム開始位置で実際に表示される区間（トリミングで頭を落とすと、
                // それより手前の区切りは再生されない）。番号はここから描き始める
                val firstVisible = texts.indexOfLast { it.startMs <= startMs }.coerceAtLeast(0)

                texts.forEachIndexed { index, segment ->
                    val splitX = track.msToX(segment.startMs, durationMs)
                    if (index > 0) {
                        drawLine(
                            color = splitColor,
                            start = Offset(splitX, 0f),
                            end = Offset(splitX, size.height),
                            strokeWidth = if (index == activeSplitIndex) 4.5.dp.toPx() else 2.5.dp.toPx()
                        )
                    }
                    // トリム範囲より手前の区切りは番号を出さない。表示されない文字だから。
                    if (index < firstVisible) return@forEachIndexed

                    // いま表示中の区間の番号は、実際の区切り位置ではなくトリム開始位置に
                    // 追従させる。そうしないとトリムを動かしても左端に張り付いたままになる
                    val anchorX = if (index == firstVisible) startX else splitX + 3.dp.toPx()
                    drawSegmentNumber(
                        measurer = textMeasurer,
                        number = index + 1,
                        anchorX = anchorX,
                        fill = splitColor,
                        label = splitLabelColor
                    )
                }
            }

            // --- 再生ヘッド。上端の丸で「つまんで動かせる」ことを示す ---
            if (positionMs in startMs..endMs) {
                val playheadX = track.msToX(positionMs, durationMs)
                drawLine(
                    playheadColor,
                    Offset(playheadX, railHeight),
                    Offset(playheadX, size.height - railHeight),
                    2.dp.toPx()
                )
                drawCircle(
                    color = playheadColor,
                    radius = 4.dp.toPx(),
                    center = Offset(playheadX, railHeight + 4.dp.toPx())
                )
            }

            // --- つまみ ---
            drawTrimHandle(
                centerX = startX,
                halfWidth = handleHalfPx,
                grown = activeHandle == TrimHandle.Start,
                fill = handleColor,
                grip = gripColor
            )
            drawTrimHandle(
                centerX = endX,
                halfWidth = handleHalfPx,
                grown = activeHandle == TrimHandle.End,
                fill = handleColor,
                grip = gripColor
            )
        }

        // 波形が出せないときの注記。つまみと桟は描いたままにしてあるので、
        // 読み込みが終わっていなくてもトリミングはできる。
        when {
            isLoading -> WaveformNote {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                NoteText("波形を読み込み中…")
            }

            waveform == null -> WaveformNote { NoteText("波形を取得できませんでした") }

            !waveform.hasAudio -> WaveformNote { NoteText("音声なし") }
        }
    }
}

/**
 * 区間の頭に振る 1, 2, 3… の番号。
 *
 * 波形の棒に重なっても読めるよう、線と同じ紫の丸ピルを敷いてから文字を載せる。
 * 端に寄りすぎた番号は波形の中へ押し戻す（枠外へ出ると切れて読めなくなる）。
 */
private fun DrawScope.drawSegmentNumber(
    measurer: TextMeasurer,
    number: Int,
    anchorX: Float,
    fill: Color,
    label: Color
) {
    val layout = measurer.measure(
        text = number.toString(),
        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold)
    )
    val paddingX = 4.dp.toPx()
    val paddingY = 1.dp.toPx()
    val badgeWidth = layout.size.width + paddingX * 2f
    val badgeHeight = layout.size.height + paddingY * 2f
    val left = anchorX.coerceIn(0f, (size.width - badgeWidth).coerceAtLeast(0f))
    val top = 2.dp.toPx()

    drawRoundRect(
        color = fill,
        topLeft = Offset(left, top),
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(badgeHeight / 2f)
    )
    drawText(
        textLayoutResult = layout,
        color = label,
        topLeft = Offset(left + paddingX, top + paddingY)
    )
}

/**
 * ひとことの区切りに使う紫。
 *
 * 波形のprimaryと同じ色にすると切れ目が埋もれて読めないため、
 * ライトでは一段濃く、ダークでは（濃い紫が背景に沈むので）同系色で明るくする。
 */
@Composable
private fun splitMarkerColor(): Color =
    if (isSystemInDarkTheme()) DarkSplitMarker else LightSplitMarker

/** [splitMarkerColor] を下地にしたときの文字色 */
@Composable
private fun onSplitMarkerColor(): Color =
    if (isSystemInDarkTheme()) DarkOnSplitMarker else LightOnSplitMarker

/** 波形に重ねる小さな注記の下地 */
@Composable
private fun WaveformNote(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        content = content
    )
}

@Composable
private fun NoteText(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * つまみの中心を置ける範囲。
 * 左右をつまみの半分ぶん内側にしてあるので、0%・100%でも端が切れない。
 */
private class TrackMetrics(val left: Float, val right: Float) {
    val width: Float get() = (right - left).coerceAtLeast(1f)

    fun msToX(ms: Long, durationMs: Long): Float =
        left + (ms.toFloat() / durationMs.coerceAtLeast(1L)) * width

    fun xToMs(x: Float, durationMs: Long): Long =
        (((x - left) / width) * durationMs).toLong().coerceIn(0L, durationMs)
}

private fun trackMetrics(totalWidth: Float, handleHalfPx: Float) =
    TrackMetrics(handleHalfPx, (totalWidth - handleHalfPx).coerceAtLeast(handleHalfPx + 1f))

/** [awaitSlopOrRelease] の結果。長押し（動かさず時間切れ）はこれとは別に呼び出し側で判定する */
private sealed interface DragOutcome {
    data object Released : DragOutcome
    data class Dragged(val change: PointerInputChange) : DragOutcome
}

/**
 * 指を離すまで、動くたびに [onMove] を呼び続ける。
 *
 * つまみ・分割ライン・区間ごと移動で共通の骨組み。掴んだ対象ごとに違うのは
 * 「動いたときに何をするか」だけなので、待ち受けと終了条件はここに1本化する。
 *
 * イベントを consume するのは、同じ座標を親（タイムラインの縦スクロールなど）にも
 * 渡してしまうと、なぞっている最中に画面ごとスクロールしてしまうため。
 */
private suspend fun AwaitPointerEventScope.dragUntilRelease(
    pointerId: PointerId,
    onMove: (PointerInputChange) -> Unit
) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (change.changedToUpIgnoreConsumed()) return
        change.consume()
        onMove(change)
    }
}

/**
 * 指を置いたまま動かしたか、動かさずに離したかを判定する。
 *
 * 「動かさず一定時間が経った」（＝長押し）はこの関数だけでは分からない。
 * 呼び出し側で withTimeoutOrNull と組み合わせ、時間切れになったら長押しとして扱う。
 */
private suspend fun AwaitPointerEventScope.awaitSlopOrRelease(
    pointerId: PointerId,
    slopPx: Float,
    initialPosition: Offset
): DragOutcome {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return DragOutcome.Released
        if (!change.pressed) return DragOutcome.Released
        val dx = change.position.x - initialPosition.x
        val dy = change.position.y - initialPosition.y
        if (kotlin.math.abs(dx) > slopPx || kotlin.math.abs(dy) > slopPx) {
            return DragOutcome.Dragged(change)
        }
    }
}

/** 縦長の丸ピル＋中央の滑り止め2本。掴んでいる間は少しだけ太らせる */
private fun DrawScope.drawTrimHandle(
    centerX: Float,
    halfWidth: Float,
    grown: Boolean,
    fill: Color,
    grip: Color
) {
    val half = if (grown) halfWidth * 1.35f else halfWidth
    drawRoundRect(
        color = fill,
        topLeft = Offset(centerX - half, 0f),
        size = Size(half * 2f, size.height),
        cornerRadius = CornerRadius(half)
    )

    val gripHalfHeight = size.height * 0.16f
    val gripGap = half * 0.42f
    val gripWidth = (half * 0.22f).coerceAtLeast(1f)
    listOf(-gripGap, gripGap).forEach { dx ->
        drawRoundRect(
            color = grip,
            topLeft = Offset(centerX + dx - gripWidth / 2f, size.height / 2f - gripHalfHeight),
            size = Size(gripWidth, gripHalfHeight * 2f),
            cornerRadius = CornerRadius(gripWidth / 2f)
        )
    }
}

/** タイムライン操作バーの仕切り */
@Composable
private fun TimelineDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * 操作バー用の小さめアイコンボタン。
 *
 * IconButtonは48dp固定で、6個並べると横幅の狭い端末で見出しごと押し出されてしまう。
 */
@Composable
private fun TimelineIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier = modifier
            .size(TOOLBAR_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            // 無効時はM3の既定と同じ38%まで落として、押せないことを色で示す
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * オン/オフを持つ操作バーのボタン。
 *
 * 他がすべて「押したら1回起きる」動作なので、状態を持つこれだけは
 * オンのとき下地を塗って区別する（M3のicon toggle buttonと同じ見せ方）。
 */
@Composable
private fun TimelineToggleButton(
    icon: ImageVector,
    checked: Boolean,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(TOOLBAR_BUTTON_SIZE)
            .clip(CircleShape)
            .background(
                if (checked && enabled) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                checked -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 一時保存の保存と読み出し。
 *
 * 自動保存とは別枠で、名前を付けた編集内容を何本か残せる。
 * ここでも動画そのものはコピーせず、URIと編集内容だけを持つので保存は一瞬で終わる。
 *
 * 保存と読み出しを1枚にまとめてあるのは、「いまの内容を置いてから別のを開く」という
 * 使い方がひと続きの動作になるため（別々の画面だと行き来が要る）。
 */
@Composable
private fun SaveLoadDialog(
    projects: List<SavedProject>,
    canSave: Boolean,
    onSave: (String) -> Unit,
    onLoad: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // 既定の保存名は開いた時刻。そのままでも後から見分けが付く
    var name by remember { mutableStateOf(formatSavedAt(System.currentTimeMillis())) }
    var pendingDelete by remember { mutableStateOf<SavedProject?>(null) }

    // 削除だけは「もとに戻す」で戻せないので確認を挟む
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(VlogIcons.Delete, contentDescription = null) },
            title = { Text("削除しますか") },
            text = { Text("「${target.name}」を削除します。元には戻せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(VlogIcons.File, contentDescription = null) },
        title = { Text("編集内容の保存") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("保存名") },
                    singleLine = true,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { onSave(name) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("いまの内容を保存") }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text("保存した内容", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))

                if (projects.isEmpty()) {
                    Text(
                        "まだありません",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 件数は上限20なので、まとめてスクロールできれば足りる
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        projects.forEach { project ->
                            SavedProjectRow(
                                project = project,
                                onLoad = { onLoad(project.id) },
                                onDelete = { pendingDelete = project }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

/** 保存1件ぶんの行。行そのものが「読み出す」ボタンを兼ねる */
@Composable
private fun SavedProjectRow(
    project: SavedProject,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onLoad,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${project.clipCount}本・${formatSeconds(project.totalMs)}" +
                            "　${formatSavedAt(project.savedAt)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TimelineIconButton(
                icon = VlogIcons.Delete,
                contentDescription = "「${project.name}」を削除",
                enabled = true,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun RemoveAllDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(VlogIcons.DeleteSweep, contentDescription = null) },
        title = { Text("すべて削除しますか") },
        text = { Text("タイムラインの動画をすべて外します。「もとに戻す」で元に戻せます。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("すべて削除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/**
 * ひとことの入力欄。
 *
 * 分割してある場合に編集できるのは「再生ヘッドが指している区間」だけ。
 * プレビューに出ている文字と編集対象を揃えておかないと、
 * 打った文字がどこに出るのか画面から読み取れなくなる。
 */
@Composable
private fun EditorPane(
    selectedClip: VlogClip?,
    positionMs: Long,
    isExporting: Boolean,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segmentCount = selectedClip?.texts?.size ?: 1
    val segmentNumber = (selectedClip?.textIndexAt(positionMs) ?: 0) + 1

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("ひとこと", style = MaterialTheme.typography.titleMedium)
                // 分割しているときだけ、いま何番目を触っているのかを出す
                if (segmentCount > 1) {
                    Text(
                        "$segmentNumber",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSplitMarkerColor(),
                        modifier = Modifier
                            .background(splitMarkerColor(), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    Text(
                        "／$segmentCount 区間目を編集中",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = selectedClip?.textAt(positionMs) ?: "",
                onValueChange = onTextChange,
                enabled = selectedClip != null && !isExporting,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

/**
 * 「最初に開く場所」を指定できるようにしたドキュメントピッカー。
 *
 * 既定では「最近使用したファイル」が開き、撮影した動画に辿り着くまでに
 * ドロワーを開いて階層を降りる必要がある。カメラの保存先を初期表示にして
 * その手間を無くす。
 *
 * EXTRA_INITIAL_URI が効くのは API 26 以上。それ以前や、指定先が存在しない端末では
 * 単に無視されて従来どおりの画面が開くだけなので、フォールバックは要らない。
 */
private class OpenVideosFromCamera : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:DCIM/Camera"
                    )
                )
            }
        }
}
