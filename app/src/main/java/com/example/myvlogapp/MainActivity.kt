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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
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
import androidx.compose.ui.platform.ViewConfiguration
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.myvlogapp.ui.theme.DarkOnSplitMarker
import com.example.myvlogapp.ui.theme.DarkSplitMarker
import com.example.myvlogapp.ui.theme.LightOnSplitMarker
import com.example.myvlogapp.ui.theme.LightSplitMarker
import com.example.myvlogapp.ui.theme.MyVlogAppTheme
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

// =====================================================================================
// レイアウト定数
//
// 使用箇所より大幅に後ろに埋もれていると見つけにくいため、ファイル先頭に集約する。
// =====================================================================================

/**
 * 操作バーのボタン1個の大きさ。
 * Materialの推奨は48dpだが、ボタンを並べると横幅の狭い端末で行からはみ出してしまう。
 * アイコン自体は[TOOLBAR_ICON_SIZE]あるので、密なツールバーとしては触れる範囲に収まっている。
 */
private val TOOLBAR_BUTTON_SIZE = 32.dp

/** 操作バーのアイコンサイズ */
private val TOOLBAR_ICON_SIZE = 20.dp

/** セクション間の余白。プレビュー/タイムライン/ひとこと欄の区切りで共通に使う */
private val SECTION_GAP = 12.dp

/** 波形の高さ。つまみを指で掴める大きさが要るので、表示だけだった頃より厚くしてある */
private val WAVEFORM_HEIGHT = 76.dp

// トリミングつまみの寸法・ズームの余白など、波形トリマー固有の定数は
// WaveformTrimmer.kt側に集約してある（MIN_TRIM_MSだけはTrimSectionでも使うため公開）。

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
 * assets/[FONT_ASSET_DIR]/ 以下のフォントファイルからFontFamilyを作る。
 * プレビューにも書き出しと同じフォントを使う（既定フォントのままだと、
 * サイズを合わせても書き出し結果と別物に見えてしまう）。
 */
@Composable
private fun rememberAssetFontFamily(assetName: String): FontFamily {
    val context = LocalContext.current
    return remember(context, assetName) {
        FontFamily(Font(path = "$FONT_ASSET_DIR/$assetName", assetManager = context.assets))
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
    val hitokotoFontFamily = rememberAssetFontFamily(TITLE_FONT_ASSET)
    val timeFontFamily = rememberAssetFontFamily(TIME_FONT_ASSET)

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

    // 書き出し中はフォアグラウンドサービスの通知を出す（VlogExportService）。
    // Android 13以降は表示に実行時許可が要るため、書き出し開始前にリクエストする。
    // 拒否されても書き出し自体は行われる（通知が出ないだけ）。
    //
    // ここ（VlogAppScreen）で1つだけ持つ理由：以前はPreviewSection内で
    // rememberLauncherForActivityResultしていたが、PreviewSectionは縦画面では
    // Column直下、横画面ではRow>Column>PreviewSectionと呼び出し位置(親構造)が
    // isWideの切り替えで変わる。Composeはこれを別インスタンスとして扱うため、
    // Foldデバイスの開閉などでisWideが反転すると、表示中の権限ダイアログの
    // 結果コールバックがActivityResultRegistryごと失われてしまっていた。
    // VlogAppScreenはisWideの分岐より外側で1度しか呼ばれないため、ここに置けば消えない。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒否されても書き出しは続行するので結果は無視してよい */ }
    val onExport = { includeTitle: Boolean ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.export(includeTitle)
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

    VlogAppDialogs(
        showGallery = showGallery,
        galleryReloadToken = galleryReloadToken,
        onDismissGallery = { showGallery = false },
        onPickFromGallery = { uris ->
            showGallery = false
            viewModel.addClips(uris)
        },
        onUseFilePicker = {
            showGallery = false
            filePicker.launch(arrayOf("video/*"))
        },
        // 権限を再リクエストすると、システムの「動画を選択」画面が再表示される
        onChangeSelection = { permissionLauncher.launch(mediaPermissions) },
        showSaves = showSaves,
        onDismissSaves = { showSaves = false },
        canSaveProject = clips.isNotEmpty() && !isExporting,
        onLoadProject = { id ->
            showSaves = false
            viewModel.loadProject(id)
        },
        viewModel = viewModel
    )

    VlogAppSideEffects(viewModel = viewModel, clips = clips)

    // 縦横の判定にはConfigurationの画面サイズを使う。
    // BoxWithConstraintsの実測値はキーボードのぶん縮むため、そちらで判定すると
    // Foldの展開時（ほぼ正方形）にキーボードを出した瞬間へ縦→横と判定が裏返り、
    // レイアウトごと作り直されて入力欄のフォーカスが飛んでしまう。
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp > configuration.screenHeightDp

    // ひとことはクリップの途中で切り替わるので、再生位置を見て出し分ける。
    // 以前はPreviewSection/EditSection/TimelinePaneがそれぞれ自分でcollectしていたが、
    // 同じ値を3箇所で購読しているだけなのでここ1箇所にまとめて引数で渡す。
    val positionMs by viewModel.playbackPositionMs.collectAsStateWithLifecycle()

    // isWide(縦画面はColumn直下、横画面はRow>Columnの中)で親構造が変わっても
    // 中身（PreviewSection/EditSection）は完全に同じなので、呼び出し部分を
    // ローカルラムダに一本化する。以前は縦横それぞれに引数リストを丸ごと
    // 書き写しており、片方だけ引数を足し忘れる事故の元だった。
    val preview: @Composable ColumnScope.(previewWeight: Float) -> Unit = { weight ->
        PreviewSection(
            selectedClip = selectedClip,
            viewModel = viewModel,
            hitokotoFontFamily = hitokotoFontFamily,
            timeFontFamily = timeFontFamily,
            positionMs = positionMs,
            exportState = exportState,
            isExporting = isExporting,
            canExport = clips.isNotEmpty(),
            previewWeight = weight,
            onAdd = openGallery,
            onOpenSaves = { showSaves = true },
            onExport = onExport
        )
    }
    val edit: @Composable ColumnScope.() -> Unit = {
        EditSection(
            viewModel = viewModel,
            clips = clips,
            selectedIndex = selectedIndex,
            selectedClip = selectedClip,
            positionMs = positionMs,
            isExporting = isExporting,
            timelineWeight = timelineWeight,
            editorWeight = editorWeight
        )
    }

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
                // 横並びのときはプレビューが左ペインを丸ごと使える(weight=1f)
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) { preview(1f) }

                // ── 右ペイン：タイムラインとひとこと ──────────────────
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) { edit() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                preview(previewWeight)
                Spacer(Modifier.height(SECTION_GAP))
                edit()
            }
        }
    }
}

/**
 * 動画を選ぶギャラリー・一時保存の一覧、2つのダイアログをまとめたもの。
 * [VlogAppScreen] から切り出したもの。
 *
 * ここではダイアログの開閉状態と結果コールバックだけを受け取り、
 * `rememberLauncherForActivityResult` 自体は [VlogAppScreen] 側に置いたままにしてある。
 * ランチャーをこの関数の中で生成すると、呼び出し位置がisWide分岐の外側であっても
 * このコンポーザブル自体が再生成されるたびにActivityResultRegistryとの紐付けが
 * 作り直されるおそれがあり、権限ダイアログのコールバックが失われるリスクを避けるため。
 */
@Composable
private fun VlogAppDialogs(
    showGallery: Boolean,
    galleryReloadToken: Int,
    onDismissGallery: () -> Unit,
    onPickFromGallery: (List<Uri>) -> Unit,
    onUseFilePicker: () -> Unit,
    onChangeSelection: () -> Unit,
    showSaves: Boolean,
    onDismissSaves: () -> Unit,
    canSaveProject: Boolean,
    onLoadProject: (Long) -> Unit,
    viewModel: VlogViewModel
) {
    if (showGallery) {
        GalleryPickerDialog(
            reloadToken = galleryReloadToken,
            onDismiss = onDismissGallery,
            onPick = onPickFromGallery,
            onUseFilePicker = onUseFilePicker,
            onChangeSelection = onChangeSelection
        )
    }

    if (showSaves) {
        val projects by viewModel.projects.collectAsStateWithLifecycle()
        // 開くたびに読み直す。保存・削除のたびにViewModel側でも更新される
        LaunchedEffect(Unit) { viewModel.refreshProjects() }

        SaveLoadDialog(
            projects = projects,
            canSave = canSaveProject,
            onSave = viewModel::saveProject,
            onLoad = onLoadProject,
            onOverwrite = { project -> viewModel.overwriteProject(project.id, project.name) },
            onDelete = viewModel::deleteProject,
            onDismiss = onDismissSaves
        )
    }
}

/**
 * Toast通知・戻るボタン・バックグラウンド時の一時停止・再生位置ポーリングをまとめたもの。
 * [VlogAppScreen] から切り出したもの。
 */
@Composable
private fun VlogAppSideEffects(viewModel: VlogViewModel, clips: List<VlogClip>) {
    val context = LocalContext.current

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
    //
    // repeatOnLifecycleで囲むのは、アプリをバックグラウンドに回しても
    // （BackHandlerでmoveTaskToBackした場合など）この無限ループ自体は
    // Composition生存中ずっと動き続け、上のDisposableEffectが再生こそ止めるものの
    // PLAYBACK_POLL_INTERVAL_MS間隔のポーリングは止まらず無駄にCPU/バッテリーを
    // 消費していたため。STARTED未満（バックグラウンド）になると自動的に一時停止し、
    // 前面に戻ると再開する。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(PLAYBACK_POLL_INTERVAL_MS)
                viewModel.refreshPlaybackProgress()
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
    positionMs: Long,
    exportState: ExportState,
    isExporting: Boolean,
    canExport: Boolean,
    previewWeight: Float,
    onAdd: () -> Unit,
    onOpenSaves: () -> Unit,
    onExport: (includeTitle: Boolean) -> Unit
) {
    PreviewPane(
        selectedClip = selectedClip,
        positionMs = positionMs,
        player = viewModel.player,
        hitokotoFontFamily = hitokotoFontFamily,
        timeFontFamily = timeFontFamily,
        modifier = Modifier.fillMaxWidth().weight(previewWeight)
    )
    Spacer(Modifier.height(SECTION_GAP))
    ActionButtons(
        isExporting = isExporting,
        canExport = canExport,
        onAdd = onAdd,
        onOpenSaves = onOpenSaves,
        onExport = onExport,
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
    positionMs: Long,
    isExporting: Boolean,
    timelineWeight: Float,
    editorWeight: Float
) {
    TimelinePane(
        viewModel = viewModel,
        clips = clips,
        selectedIndex = selectedIndex,
        positionMs = positionMs,
        isExporting = isExporting,
        modifier = Modifier.fillMaxWidth().weight(timelineWeight)
    )
    Spacer(Modifier.height(SECTION_GAP))
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
    onExport: (includeTitle: Boolean) -> Unit,
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
                modifier = Modifier.size(TOOLBAR_ICON_SIZE)
            )
        }

        if (isExporting) {
            OutlinedButton(
                onClick = onCancel,
                contentPadding = labelPadding,
                modifier = Modifier.weight(1f)
            ) { Text("中止", maxLines = 1) }
        } else {
            ExportButton(
                enabled = canExport,
                contentPadding = labelPadding,
                onExport = onExport,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 書き出しボタン。タップ＝タイトルカードあり、長押し＝タイトルカードなしで書き出す。
 * 通常の[Button]は長押しを扱えないため、見た目だけ真似た[Surface]を
 * [combinedClickable]で組んでいる。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExportButton(
    enabled: Boolean,
    contentPadding: PaddingValues,
    onExport: (includeTitle: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = ButtonDefaults.MinHeight)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = "書き出し（タイトルあり）",
                onLongClickLabel = "タイトルなしで書き出し",
                onLongClick = { onExport(false) },
                onClick = { onExport(true) }
            ),
        shape = ButtonDefaults.shape,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ) {
        Box(modifier = Modifier.padding(contentPadding), contentAlignment = Alignment.Center) {
            Text("書き出し", maxLines = 1)
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
    positionMs: Long,
    isExporting: Boolean,
    modifier: Modifier = Modifier
) {
    val selectedClip = clips.getOrNull(selectedIndex)
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val waveforms by viewModel.waveforms.collectAsStateWithLifecycle()
    val autoAdvance by viewModel.autoAdvance.collectAsStateWithLifecycle()
    val timelineMuted by viewModel.timelineMuted.collectAsStateWithLifecycle()

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
            // 1行に収めていた頃は、ボタンが増えた時点で横幅の狭い端末では
            // 見出しが「タ…」まで潰れ、それでもボタンが画面外へはみ出していた。
            Text(
                "タイムライン",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            TimelineToolbar(
                viewModel = viewModel,
                clips = clips,
                selectedIndex = selectedIndex,
                selectedClip = selectedClip,
                positionMs = positionMs,
                autoAdvance = autoAdvance,
                timelineMuted = timelineMuted,
                canUndo = canUndo,
                canRedo = canRedo,
                isExporting = isExporting,
                onRequestRemoveAll = { confirmRemoveAll = true }
            )

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
                        ClipTile(
                            clip = clip,
                            isSelected = index == selectedIndex,
                            onClick = { viewModel.select(index) },
                            onLongClick = { viewModel.toggleClipMute(clip.id) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                selectedClip?.let { clip ->
                    val key = clip.uri.toString()
                    TrimSection(
                        clip = clip,
                        waveform = waveforms[key],
                        isWaveformLoading = !waveforms.containsKey(key),
                        positionMs = positionMs,
                        isExporting = isExporting,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

/**
 * 選択中クリップのトリム表示部分。[TimelinePane] から切り出したもの。
 *
 * MIN_TRIM_MS未満の動画はトリムハンドルの可動域が無くなり、ドラッグ時に
 * coerceIn(min, max)のmin>maxで例外を起こす余地があるため、波形トリマー自体を出さない。
 */
@Composable
private fun TrimSection(
    clip: VlogClip,
    waveform: Waveform?,
    isWaveformLoading: Boolean,
    positionMs: Long,
    isExporting: Boolean,
    viewModel: VlogViewModel
) {
    when {
        clip.durationMs >= MIN_TRIM_MS -> {
            Text(
                text = "${clip.timeText}：" +
                        "${formatSeconds(clip.startMs)} 〜 ${formatSeconds(clip.endMs)}" +
                        "（${formatSeconds(clip.trimmedDurationMs)}）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            WaveformTrimmer(
                clipId = clip.id,
                waveform = waveform,
                isLoading = isWaveformLoading,
                texts = clip.texts,
                durationMs = clip.durationMs,
                startMs = clip.startMs,
                endMs = clip.endMs,
                positionMs = positionMs,
                enabled = !isExporting,
                callbacks = WaveformTrimmerCallbacks(
                    onTrimChange = viewModel::updateTrim,
                    onTrimMove = viewModel::moveTrim,
                    onSplitMove = viewModel::moveSplit,
                    onSeek = viewModel::seekWithinTrim,
                    onScrubStart = viewModel::beginScrub,
                    onScrubEnd = viewModel::endScrub
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WAVEFORM_HEIGHT)
                    .padding(top = 8.dp)
            )
        }

        clip.durationMs <= 0 -> {
            Text(
                "この動画は長さを取得できませんでした",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        else -> {
            Text(
                "この動画は短すぎてトリミングできません",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * タイムラインの操作バー。削除・入れ替え・連続再生・もとに戻す/やり直す・
 * 2s/4sプリセット・ひとこと分割をまとめて並べる。[TimelinePane] から切り出したもの。
 * よく使う2s/4sプリセットとひとこと分割は右端に、削除系は左端に配置している。
 */
@Composable
private fun TimelineToolbar(
    viewModel: VlogViewModel,
    clips: List<VlogClip>,
    selectedIndex: Int,
    selectedClip: VlogClip?,
    positionMs: Long,
    autoAdvance: Boolean,
    timelineMuted: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isExporting: Boolean,
    onRequestRemoveAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val enabled = selectedClip != null && !isExporting
        val trimPresetEnabled = enabled && (selectedClip?.durationMs ?: 0L) > 0L

        // 並びは 削除 → すべて削除 → 入れ替え → 連続再生 → もとに戻す → やり直す
        //        → 2s/4sプリセット → ひとことを分割
        // よく使う2s/4sプリセットとひとこと分割を右端に、誤タップが怖い
        // 削除系は逆に左端に置いて、頻用操作を巻き込まないようにしている。

        // 押し間違えても「もとに戻す」で復帰できるので、1件の削除は確認なしで消す
        CompactIconButton(
            icon = VlogIcons.Delete,
            contentDescription = "選択中のクリップを削除",
            enabled = enabled,
            onClick = viewModel::removeSelected,
            tint = MaterialTheme.colorScheme.error
        )
        CompactIconButton(
            icon = VlogIcons.DeleteSweep,
            contentDescription = "すべて削除",
            enabled = clips.isNotEmpty() && !isExporting,
            onClick = onRequestRemoveAll,
            tint = MaterialTheme.colorScheme.error
        )

        TimelineDivider()

        CompactIconButton(
            icon = VlogIcons.MoveLeft,
            contentDescription = "ひとつ前へ移動",
            enabled = enabled && selectedIndex > 0,
            onClick = { viewModel.moveSelected(-1) }
        )
        CompactIconButton(
            icon = VlogIcons.MoveRight,
            contentDescription = "ひとつ後ろへ移動",
            enabled = enabled && selectedIndex < clips.lastIndex,
            onClick = { viewModel.moveSelected(1) }
        )

        TimelineDivider()

        // タイムラインのミュートと連続再生を1グループにまとめ、連続再生を右に置く
        TimelineToggleButton(
            icon = if (timelineMuted) VlogIcons.VolumeOff else VlogIcons.VolumeUp,
            checked = timelineMuted,
            contentDescription = if (timelineMuted) {
                "タイムラインのミュート：オン（プレビューと書き出しの音を消します）"
            } else {
                "タイムラインのミュート：オフ"
            },
            enabled = clips.isNotEmpty() && !isExporting,
            onClick = { viewModel.setTimelineMuted(!timelineMuted) }
        )
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

        CompactIconButton(
            icon = VlogIcons.Undo,
            contentDescription = "もとに戻す",
            enabled = canUndo && !isExporting,
            onClick = viewModel::undo
        )
        CompactIconButton(
            icon = VlogIcons.Redo,
            contentDescription = "やり直す",
            enabled = canRedo && !isExporting,
            onClick = viewModel::redo
        )

        TimelineDivider()

        TrimPresetButton(
            label = "2s",
            contentDescription = "先頭から2秒を選択",
            enabled = trimPresetEnabled,
            onClick = { viewModel.applyTrimPreset(2_000L) }
        )
        TrimPresetButton(
            label = "4s",
            contentDescription = "先頭から4秒を選択",
            enabled = trimPresetEnabled,
            onClick = { viewModel.applyTrimPreset(4_000L) }
        )

        TimelineDivider()

        // 再生ヘッドが区切りの上にあるときは、同じボタンが解除に変わる。
        // 区切りを消す手段が「もとに戻す」しか無いと、あとから直せなくなるため。
        val splitOnPlayhead = selectedClip?.splitPointNear(positionMs)
        CompactIconButton(
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
    }
}

/**
 * タイムラインのクリップ1件ぶんのタイル。[TimelinePane] から切り出したもの。
 * タップで選択、長押しでそのクリップのミュートを切り替える。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipTile(clip: VlogClip, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        // 高さは中身に任せる。固定にすると端末の文字サイズ設定を
        // 上げたときに尺の行がタイルからはみ出して切れる
        modifier = Modifier
            .width(104.dp)
            .combinedClickable(
                onClickLabel = "選択",
                onLongClickLabel = if (clip.isMuted) "ミュートを解除" else "ミュート",
                onLongClick = onLongClick,
                onClick = onClick
            ),
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
                    SegmentBadge(
                        "1-${clip.texts.size}",
                        fontSize = 9.sp,
                        horizontalPadding = 4.dp
                    )
                }
                // ミュート中のクリップは長押ししないと気付けないので、常時アイコンで示す
                if (clip.isMuted) {
                    Icon(
                        VlogIcons.VolumeOff,
                        contentDescription = "ミュート中",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * ひとことの区切りに使う紫。
 *
 * 波形のprimaryと同じ色にすると切れ目が埋もれて読めないため、
 * ライトでは一段濃く、ダークでは（濃い紫が背景に沈むので）同系色で明るくする。
 */
@Composable
fun splitMarkerColor(): Color =
    if (isSystemInDarkTheme()) DarkSplitMarker else LightSplitMarker

/** [splitMarkerColor] を下地にしたときの文字色。WaveformTrimmer.kt側からも使うため公開している */
@Composable
fun onSplitMarkerColor(): Color =
    if (isSystemInDarkTheme()) DarkOnSplitMarker else LightOnSplitMarker

/**
 * 分割済みの区間を示す紫のバッジ。
 * タイムラインのクリップタイル（"1-3"のような区間数）と、
 * 「ひとこと」入力欄（"2"のような編集中の区間番号）の2箇所で共通の見た目を使う。
 */
@Composable
private fun SegmentBadge(
    text: String,
    fontSize: TextUnit,
    horizontalPadding: Dp
) {
    Text(
        text,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = onSplitMarkerColor(),
        modifier = Modifier
            .background(splitMarkerColor(), RoundedCornerShape(50))
            .padding(horizontal = horizontalPadding, vertical = 1.dp)
    )
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
 * 操作バーのボタンの土台。円形の当たり判定＋背景色だけを担い、
 * 中身（アイコンと色）はCompactIconButton/TimelineToggleButtonそれぞれに任せる。
 */
@Composable
private fun ToolbarButtonBox(
    background: Color,
    role: Role,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(TOOLBAR_BUTTON_SIZE)
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = enabled,
                role = role,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * 操作バー用の小さめアイコンボタン。タイムラインの操作バー以外（一時保存一覧の行など）でも使う。
 *
 * IconButtonは48dp固定で、並べると横幅の狭い端末で見出しごと押し出されてしまう。
 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    ToolbarButtonBox(
        background = Color.Transparent,
        role = Role.Button,
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            // 無効時はM3の既定と同じ38%まで落として、押せないことを色で示す
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(TOOLBAR_ICON_SIZE)
        )
    }
}

/**
 * トリミングのプリセットボタン（「2s」「4s」）。
 * 他の操作バーボタンが正円のアイコンなのに対し、こちらは文字ラベルなので
 * 横幅がラベルぶん伸びる楕円にしてある。
 */
@Composable
private fun TrimPresetButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(TOOLBAR_BUTTON_SIZE)
            .clip(RoundedCornerShape(50))
            .border(1.dp, tint.copy(alpha = if (enabled) 0.6f else 0.24f), RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) tint else tint.copy(alpha = 0.38f)
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
    ToolbarButtonBox(
        background = if (checked && enabled) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        role = Role.Switch,
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                checked -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(TOOLBAR_ICON_SIZE)
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
    onOverwrite: (SavedProject) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // 既定の保存名は開いた時刻。そのままでも後から見分けが付く
    var name by remember { mutableStateOf(formatSavedAt(System.currentTimeMillis())) }
    var pendingDelete by remember { mutableStateOf<SavedProject?>(null) }

    // 削除だけは「もとに戻す」で戻せないので確認を挟む
    pendingDelete?.let { target ->
        DestructiveConfirmDialog(
            icon = VlogIcons.Delete,
            title = "削除しますか",
            message = "「${target.name}」を削除します。元には戻せません。",
            confirmLabel = "削除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDelete(target.id)
                pendingDelete = null
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
                                onOverwrite = { onOverwrite(project) },
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

/**
 * 保存1件ぶんの行。タップで「読み出す」、長押しで「上書き保存」を兼ねる
 * （上書きは確認ダイアログを出さない）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedProjectRow(
    project: SavedProject,
    onLoad: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = "読み出す",
                onLongClickLabel = "上書き保存",
                onLongClick = onOverwrite,
                onClick = onLoad
            )
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
            CompactIconButton(
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
    DestructiveConfirmDialog(
        icon = VlogIcons.DeleteSweep,
        title = "すべて削除しますか",
        message = "タイムラインの動画をすべて外します。「もとに戻す」で元に戻せます。",
        confirmLabel = "すべて削除",
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

/**
 * 取り消せない操作の確認ダイアログ。
 * 「削除しますか」（一時保存の削除）と「すべて削除しますか」で見た目が同じだったのを共通化。
 */
@Composable
private fun DestructiveConfirmDialog(
    icon: ImageVector,
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text(confirmLabel) }
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
                Text("ひとこと", style = MaterialTheme.typography.titleSmall)
                // 分割しているときだけ、いま何番目を触っているのかを出す
                if (segmentCount > 1) {
                    SegmentBadge("$segmentNumber", fontSize = 11.sp, horizontalPadding = 6.dp)
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
