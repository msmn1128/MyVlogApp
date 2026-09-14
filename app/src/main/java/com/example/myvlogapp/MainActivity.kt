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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myvlogapp.ui.theme.MyVlogAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// =====================================================================================
// レイアウト定数
//
// 使用箇所より大幅に後ろに埋もれていると見つけにくいため、ファイル先頭に集約する。
// PreviewSection.kt/TimelineSection.kt/ToolbarButtons.ktからも参照するためinternal。
// =====================================================================================

/**
 * 操作バーのボタン1個の大きさ。Materialの推奨に合わせて48dp。
 * 横幅の狭い端末ではみ出す分は、操作バー自体の横スクロールで吸収する。
 */
internal val TOOLBAR_BUTTON_SIZE = 48.dp

/** 操作バーのアイコンサイズ */
internal val TOOLBAR_ICON_SIZE = 20.dp

/** セクション間の余白。プレビュー/タイムライン/ひとこと欄の区切りで共通に使う */
internal val SECTION_GAP = 12.dp

/** 波形の高さ。つまみを指で掴める大きさが要るので、表示だけだった頃より厚くしてある */
internal val WAVEFORM_HEIGHT = 76.dp

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
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    // 全開時の高さを覚えておき、0〜1の開閉度に正規化する。
    // ComposeのWindowInsets.imeはIME自体のスライドと同じフレームで
    // 値が更新されるため、animateFloatAsStateで別途アニメーションを足すと
    // 「キーボードはもう閉じているのにレイアウトだけ遅れて縮む」ズレが出る。
    // ここではその値をそのまま補間の材料にして、キーボードの動きと
    // 完全に同期させている。
    var imeMaxBottomPx by remember { mutableIntStateOf(0) }
    if (imeBottomPx > imeMaxBottomPx) imeMaxBottomPx = imeBottomPx
    val imeOpenFraction =
        if (imeMaxBottomPx > 0) (imeBottomPx.toFloat() / imeMaxBottomPx).coerceIn(0f, 1f) else 0f
    // 波形を足したぶんタイムラインの取り分を増やしてある。
    // ここを削るとトリミングのスライダーがカードの下端で切れ、
    // 一度スクロールしないと尺を変えられなくなる。
    val previewWeight = lerp(0.42f, 0.25f, imeOpenFraction)
    val timelineWeight = lerp(0.40f, 0.20f, imeOpenFraction)
    val editorWeight = lerp(0.18f, 0.55f, imeOpenFraction)

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
