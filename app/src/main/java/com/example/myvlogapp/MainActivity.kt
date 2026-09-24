package com.example.myvlogapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myvlogapp.data.hasMediaAccess
import com.example.myvlogapp.data.mediaPermissions
import com.example.myvlogapp.export.ExportState
import com.example.myvlogapp.ui.screens.EditSection
import com.example.myvlogapp.ui.screens.PreviewSection
import com.example.myvlogapp.ui.screens.TimelineActions
import com.example.myvlogapp.ui.screens.TimelineFit
import com.example.myvlogapp.ui.screens.TimelineState
import com.example.myvlogapp.ui.screens.TitleCreationDialog
import com.example.myvlogapp.ui.theme.MyVlogAppTheme
import com.example.myvlogapp.waveform.WaveformTrimmerCallbacks

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
 *   プレビュー 40% → ボタン → タイムライン 40% → ひとこと 20%
 *   （キーボードを開くとひとことへ高さを回し、中身が収まらなければタイムラインを広げる）
 *
 * 横長（Foldの展開時 / タブレット / 横向き）: 2ペイン
 *   左にプレビューとボタン、右にタイムラインとひとこと。
 *   1カラムのまま縦に4分割すると、縦が短い端末でタイムラインが潰れて
 *   スライダーや文字が入り切らなくなるため。
 */
// imeAnimationSource/Target（キーボードの開閉度を求めるのに使う）が実験的なAPIのため
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VlogAppScreen(viewModel: VlogViewModel = viewModel()) {
    val context = LocalContext.current
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val isAdding by viewModel.isAdding.collectAsStateWithLifecycle()
    val selectedClip = clips.getOrNull(selectedIndex)
    val isExporting = exportState is ExportState.Running

    // キーボードが出ている間は、ひとこと入力欄に画面を大きく割り当てる。
    // safeDrawingPaddingがIMEのぶんだけ表示領域を詰めるため、比率が固定のままだと
    // セクションが一様に潰れて入力欄が読めなくなってしまう。
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    // 全開時の高さで割って、0〜1の開閉度に正規化する。
    // ComposeのWindowInsets.imeはIME自体のスライドと同じフレームで
    // 値が更新されるため、animateFloatAsStateで別途アニメーションを足すと
    // 「キーボードはもう閉じているのにレイアウトだけ遅れて縮む」ズレが出る。
    // ここではその値をそのまま補間の材料にして、キーボードの動きと
    // 完全に同期させている。
    //
    // 全開時の高さは、IMEのアニメーションの出発点と行き先（imeAnimationSource/Target）の大きい方。
    // 開くときは行き先、閉じるときは出発点が全開時の高さを指し、アニメーションしていないときは
    // どちらも今の高さになる。以前は「これまでで一番高かった値」を覚えていたが、回転や
    // 絵文字パネル（普通のキーボードより背が高い）で一度上がると下がらず、普通のキーボードを
    // 全開にしても開閉度が1に届かず、ひとこと欄が広がり切らなかった。
    // 今の高さ（imeBottomPx）も候補に入れるのは、IMEのアニメーションが届かない環境
    // （アニメーションを伝えるWindowInsetsAnimationはAndroid 11からで、minSdkのAndroid 10では
    // 互換ライブラリ頼みになる）で出発点と行き先が0のままでも、開いていれば開閉度が1になるようにするため。
    val imeFullPx = maxOf(
        imeBottomPx,
        WindowInsets.imeAnimationSource.getBottom(density),
        WindowInsets.imeAnimationTarget.getBottom(density)
    )
    val imeOpenFraction =
        if (imeFullPx > 0) (imeBottomPx.toFloat() / imeFullPx).coerceIn(0f, 1f) else 0f
    // ひとこと入力欄が、キーボードの開閉に合わせて自分の表示（placeholderの出し分けや
    // スクロール位置）を切り替えるための合図。isFocusedだけを見ると、キーボードを閉じても
    // フォーカスは残ったままなことがあり、空欄でもカーソルだけ点滅し続けてしまう。
    val isImeVisible = imeBottomPx > 0
    // 縦横の判定（isWide）にはウィンドウ全体の大きさ（containerSize）を使う。
    // BoxWithConstraintsの実測値はキーボードのぶん縮むため、そちらで判定すると
    // Foldの展開時（ほぼ正方形）にキーボードを出した瞬間へ縦→横と判定が裏返り、
    // レイアウトごと作り直されて入力欄のフォーカスが飛んでしまう。
    // ウィンドウ自体はキーボードでは縮まない（insetsとして渡される）ので、こちらは裏返らない。
    val windowSize = LocalWindowInfo.current.containerSize
    val isWide = windowSize.width > windowSize.height
    // キーボードが出ている間は、次の2つの画面でタイムラインを畳み、ひとこと欄に高さを回す。
    // - 縦に短い画面（横向きのスマホなど。Materialの区分でcompactにあたる480dp未満）：
    //   残りが数百pxしかなく、比率を変えてもタイムラインとひとこと欄が両方潰れ、入力欄が
    //   枠線1本ほどになって打った文字が見えなかった。ひとこと欄の見出しも畳む
    // - 横2ペイン（Foldを開いた画面など）：右ペインのタイムライン欄が見出しと操作バーだけの
    //   高さまで縮み、「2s」「4s」が上下で切れて見えた（実機 SM-F971Q）。こちらは高さに余裕が
    //   あるので、ひとこと欄の見出し（何区間目を編集中か）は残す
    val isCompactHeight = with(density) { windowSize.height.toDp() } < COMPACT_HEIGHT
    val showTimeline = !((isCompactHeight || isWide) && isImeVisible)
    val showEditorHeader = !(isCompactHeight && isImeVisible)
    // 波形を足したぶんタイムラインの取り分を増やしてある。
    // ここを削るとトリミングのスライダーがカードの下端で切れ、
    // 一度スクロールしないと尺を変えられなくなる。
    // 文字サイズや画面の比率によっては、この配分でも波形が欄から押し出されるので、
    // 足りないぶんをタイムラインへ上乗せする（下のtimelineExtraWeight。TimelineFit.kt）
    val basePreviewWeight = lerp(0.40f, 0.25f, imeOpenFraction)
    val baseTimelineWeight = lerp(0.40f, 0.20f, imeOpenFraction)
    val baseEditorWeight = lerp(0.20f, 0.55f, imeOpenFraction)
    val timelineFit = remember { TimelineFit() }

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
    // 書き出し（タイトルあり）を押した直後に出す、タイトル文言選択ダイアログ
    var showTitleDialog by remember { mutableStateOf(false) }
    // 許可する動画を選び直したときに一覧を取り直すための合図
    var galleryReloadToken by remember { mutableIntStateOf(0) }

    // 許可されてもされなくても、ギャラリーの画面は開く。許可されなかったときは、一覧の代わりに
    // 許可し直す導線と「ファイル」（システムのファイル選択）を案内する（GalleryPicker.kt）。
    // 以前は断られたらToastで知らせるだけで、ファイル選択の入口がギャラリーの画面の中にしか
    // 無いため、許可しなかった人は動画を1本も追加できなかった
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        showGallery = true
        galleryReloadToken++
    }

    val openGallery = {
        if (hasMediaAccess(context)) showGallery = true
        else permissionLauncher.launch(mediaPermissions)
    }

    // ここ（VlogAppScreen）で1つだけ持つ。PreviewSection内に置くと、縦画面（Column直下）と
    // 横画面（Row>Column>PreviewSection）で呼び出し位置が変わり、Composeが別インスタンスとして
    // 扱うため、Foldの開閉などでisWideが反転した瞬間に、表示中の権限ダイアログの
    // 結果コールバックがActivityResultRegistryごと失われる。
    // VlogAppScreenはisWideの分岐より外側で1度しか呼ばれないので、ここに置けば消えない。
    val startExport = rememberExportStarter(onStart = viewModel::export)
    // タイトルあり（タップ）のときだけ、文言選択ダイアログを挟む。
    // タイトルなし（長押し）はタイトルカード自体を焼かないので、そのまま書き出す。
    val onExport = { includeTitle: Boolean ->
        if (includeTitle) showTitleDialog = true else startExport(false, null)
    }

    if (showTitleDialog) {
        TitleCreationDialog(
            defaultText = clips.firstOrNull()?.dateText.orEmpty(),
            timeFontFamily = timeFontFamily,
            onDismiss = { showTitleDialog = false },
            onConfirm = { titleText ->
                showTitleDialog = false
                startExport(true, titleText)
            }
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        remember { OpenVideosFromCamera() }
    ) { uris: List<Uri> ->
        // 次にアプリを開いたときも読めるよう、永続的な読み取り権限をもらっておく。
        // これが取れたURIだけが復元対象になる（動画自体はコピーしない）。
        // 取れなかった動画は、アプリを開き直すと編集内容に残らないので、先に知らせておく。
        val notPersisted = uris.count { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isFailure
        }
        if (notPersisted > 0) {
            Toast.makeText(
                context,
                "$notPersisted 件は、アプリを開き直すと編集内容に残らない可能性があります",
                Toast.LENGTH_LONG
            ).show()
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
        // 権限を再リクエストすると、システムの許可の画面（「選択した項目のみ」なら動画の選択画面）が出る
        onRequestAccess = { permissionLauncher.launch(mediaPermissions) },
        showSaves = showSaves,
        onDismissSaves = { showSaves = false },
        canSaveProject = clips.isNotEmpty() && !isExporting && !isAdding,
        onLoadProject = { id ->
            showSaves = false
            viewModel.loadProject(id)
        },
        viewModel = viewModel
    )

    VlogAppSideEffects(viewModel = viewModel, clips = clips)

    // タイムラインへの上乗せは、縦1カラムではプレビューから、横2ペインでは同じ右ペインの
    // ひとこと欄から差し引く。どちらも削り切らない下限を残す。プレビューは動画を縮めて
    // 見せるだけで済むが、ひとこと欄は1行ぶんを割ると打った文字が見えなくなるので下限を高めにする
    // （右ペインの2割）。キーボードが開いている間は上乗せしない（ひとこと欄に高さを回す時間なので）
    val timelineExtraWeight = timelineFit.extraWeight(
        baseWeight = baseTimelineWeight,
        maxExtra = if (isWide) baseEditorWeight - MIN_WIDE_EDITOR_WEIGHT
        else basePreviewWeight - MIN_PREVIEW_WEIGHT
    ) * (1f - imeOpenFraction)
    val previewWeight = if (isWide) basePreviewWeight else basePreviewWeight - timelineExtraWeight
    val timelineWeight = baseTimelineWeight + timelineExtraWeight
    val editorWeight = if (isWide) baseEditorWeight - timelineExtraWeight else baseEditorWeight

    // ひとことはクリップの途中で切り替わるので、再生位置を見て出し分ける。
    // collectは1箇所にまとめて引数で渡す。値を読まずStateのまま渡しているのは、
    // ここで読むと再生位置が変わる（約80ms）たびにこの画面全体が再コンポーズされるため。
    // 各所で「表示する文字列」「区切りの上か」などに派生させてから読む。
    val positionMs = viewModel.playbackPositionMs.collectAsStateWithLifecycle()

    // 画面がViewModelを直接見なくて済むよう、状態（StateFlowのまま）と操作（メソッド参照）を
    // それぞれ1つのホルダーにまとめて渡す。値まで上げないのは、undo可否や波形が変わるたびに
    // 画面全体が再コンポーズされるのを避けるため（詳しくは TimelineActions.kt）。
    // rememberで1度だけ作るのが肝心で、毎コンポーズで作り直すと@Stableにした意味が無くなる。
    val timelineState = remember(viewModel) {
        TimelineState(
            canUndo = viewModel.canUndo,
            canRedo = viewModel.canRedo,
            autoAdvance = viewModel.autoAdvance,
            timelineMuted = viewModel.timelineMuted,
            selectedWaveform = viewModel.selectedWaveform,
            replacementCount = viewModel.timelineReplacementCount,
            isPlaying = viewModel.isPlaying,
            missingClipIds = viewModel.missingClipIds
        )
    }
    val timelineActions = remember(viewModel) {
        TimelineActions(
            select = viewModel::select,
            removeSelected = viewModel::removeSelected,
            removeAll = viewModel::removeAll,
            moveSelected = viewModel::moveSelected,
            toggleClipMute = viewModel::toggleClipMute,
            setTimelineMuted = viewModel::setTimelineMuted,
            setAutoAdvance = viewModel::setAutoAdvance,
            undo = viewModel::undo,
            redo = viewModel::redo,
            applyTrimPreset = viewModel::applyTrimPreset,
            splitTextAtPlayhead = viewModel::splitTextAtPlayhead,
            removeSplit = viewModel::removeSplit,
            updateText = viewModel::updateText,
            pause = viewModel::pause,
            trimmer = WaveformTrimmerCallbacks(
                onTrimChange = viewModel::updateTrim,
                onTrimMove = viewModel::moveTrim,
                onSplitMove = viewModel::moveSplit,
                onSeek = viewModel::seekWithinTrim,
                onScrubStart = viewModel::beginScrub,
                onScrubEnd = viewModel::endScrub,
                onDragStart = viewModel::beginInteractiveSeek,
                onDragEnd = viewModel::endInteractiveSeek
            )
        )
    }

    // isWide(縦画面はColumn直下、横画面はRow>Columnの中)で親構造が変わっても
    // 中身（PreviewSection/EditSection）は完全に同じなので、呼び出し部分を
    // ローカルラムダに一本化する（縦横で引数リストを別々に持つと、片方だけ
    // 引数を足し忘れる事故の元になる）。
    val preview: @Composable ColumnScope.(previewWeight: Float) -> Unit = { weight ->
        PreviewSection(
            selectedClip = selectedClip,
            player = viewModel.player,
            hitokotoFontFamily = hitokotoFontFamily,
            timeFontFamily = timeFontFamily,
            positionMs = positionMs,
            exportState = exportState,
            isExporting = isExporting,
            isAdding = isAdding,
            // 読み込み中の動画がまだタイムラインに入っていないので、その間は書き出しを始めさせない
            canExport = clips.isNotEmpty() && !isAdding,
            previewWeight = weight,
            onTogglePlayback = viewModel::togglePlayback,
            onAdd = openGallery,
            onOpenSaves = { showSaves = true },
            onExport = onExport,
            onCancelExport = viewModel::cancelExport
        )
    }
    val edit: @Composable ColumnScope.() -> Unit = {
        EditSection(
            clips = clips,
            selectedIndex = selectedIndex,
            selectedClip = selectedClip,
            positionMs = positionMs,
            state = timelineState,
            actions = timelineActions,
            isExporting = isExporting,
            timelineWeight = timelineWeight,
            editorWeight = editorWeight,
            showTimeline = showTimeline,
            showEditorHeader = showEditorHeader,
            isImeVisible = isImeVisible,
            timelineFit = timelineFit
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
