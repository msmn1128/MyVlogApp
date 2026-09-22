package com.example.myvlogapp.ui.screens

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.myvlogapp.CANVAS_HEIGHT
import com.example.myvlogapp.CANVAS_WIDTH
import com.example.myvlogapp.HITOKOTO_FONT_PT
import com.example.myvlogapp.HITOKOTO_LINE_SPACING_PT
import com.example.myvlogapp.PREVIEW_FONT_SCALE
import com.example.myvlogapp.SECTION_GAP
import com.example.myvlogapp.TIME_FONT_PT
import com.example.myvlogapp.TIME_MARGIN_PT
import com.example.myvlogapp.TOOLBAR_ICON_SIZE
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.export.ExportState
import com.example.myvlogapp.ui.components.VlogIcons

// =====================================================================================
// MainActivity.kt から切り出した、プレビュー・操作ボタン・書き出し進捗のまとまり。
// =====================================================================================

/**
 * プレビュー・操作ボタン・進捗のまとまり。
 *
 * 縦1カラムと横2ペインで中身は同じなので、それぞれの分岐に書き写さず1箇所にまとめる。
 * 分けて書いていると、片方だけ直して縦と横で挙動が食い違う事故が起きる。
 * 違うのは「プレビューに何割を割り当てるか」だけなので、そこだけ引数で受ける。
 */
@Composable
internal fun ColumnScope.PreviewSection(
    selectedClip: VlogClip?,
    player: ExoPlayer,
    hitokotoFontFamily: FontFamily,
    timeFontFamily: FontFamily,
    positionMs: State<Long>,
    exportState: ExportState,
    isExporting: Boolean,
    isAdding: Boolean,
    canExport: Boolean,
    previewWeight: Float,
    onTogglePlayback: () -> Unit,
    onAdd: () -> Unit,
    onOpenSaves: () -> Unit,
    onExport: (includeTitle: Boolean) -> Unit,
    onCancelExport: () -> Unit
) {
    PreviewPane(
        selectedClip = selectedClip,
        positionMs = positionMs,
        player = player,
        onTogglePlayback = onTogglePlayback,
        hitokotoFontFamily = hitokotoFontFamily,
        timeFontFamily = timeFontFamily,
        modifier = Modifier.fillMaxWidth().weight(previewWeight)
    )
    Spacer(Modifier.height(SECTION_GAP))
    ActionButtons(
        isExporting = isExporting,
        isAdding = isAdding,
        canExport = canExport,
        onAdd = onAdd,
        onOpenSaves = onOpenSaves,
        onExport = onExport,
        onCancel = onCancelExport
    )
    AddProgress(isAdding)
    ExportProgress(exportState)
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
    positionMs: State<Long>,
    player: ExoPlayer,
    onTogglePlayback: () -> Unit,
    hitokotoFontFamily: FontFamily,
    timeFontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    if (selectedClip == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
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

    // 再生位置は約80msごとに更新される。ここで値そのものを読むとプレビュー全体が
    // 毎回再コンポーズされてしまうため、表示する文字列だけを派生させておき、
    // ひとことが切り替わったときにだけ更新されるようにする。
    val hitokoto by remember(selectedClip) {
        derivedStateOf { selectedClip.textAt(positionMs.value) }
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
        // 書き出し側（buildClipFilterのlineHeight = HITOKOTO_FONT_PT + HITOKOTO_LINE_SPACING_PT）と
        // 同じ行間になるよう明示する。指定しないとComposeがフォントの既定の行送りを使ってしまい、
        // 2行以上になったときに書き出し結果とプレビューで行間がずれる。
        val hitokotoLineHeight = with(density) {
            ((HITOKOTO_FONT_PT + HITOKOTO_LINE_SPACING_PT) * canvasScale).toSp()
        }
        val timeSize = with(density) { (TIME_FONT_PT * canvasScale).toSp() }
        // 縦横問わずキャンバス右端基準に揃える（書き出し側と同じ位置）。
        val timeMargin = with(density) { (TIME_MARGIN_PT * canvasScale).toDp() }

        Box(
            modifier = Modifier
                .size(canvasWidth, canvasHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // iOS版に合わせ、シークバーなどの操作UIは出さずタップで再生/一時停止だけ切り替える。
            // ripple(波紋)も消す。動画の上に光る輪が出ると書き出し結果と見た目が食い違って見えるため。
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = false
                    }
                },
                // ExoPlayerはViewModelが持ち続けるので、外れたPlayerViewが
                // playerとサーフェスを握ったままにならないよう切り離す
                onRelease = { it.player = null },
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onTogglePlayback()
                    }
            )
            // ここから下は書き出しに焼き込まれる文字。配色はテーマに追従させず、
            // 出力と同じ白のままにしておく。
            Text(
                text = hitokoto,
                color = Color.White,
                fontSize = hitokotoSize,
                lineHeight = hitokotoLineHeight,
                style = LocalTextStyle.current.copy(
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                fontFamily = hitokotoFontFamily,
                textAlign = TextAlign.Center,
                // 折り返さない。書き出しのdrawtextは「\n」の位置でしか改行しないので、
                // プレビューだけ自動で折り返すと、長い1行が画面では収まって見えるのに
                // 書き出した動画では左右が切れる。はみ出しも書き出しと同じく中央から
                // 左右均等にさせる（unboundedにしないと左端から描かれて右だけが切れる）。
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.Center)
                    .wrapContentWidth(unbounded = true)
            )
            Text(
                text = selectedClip.timeText,
                color = Color.White,
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
    isAdding: Boolean,
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
            // 読み込み中に押し直すと、同じ動画を並行して読むことになる
            enabled = !isExporting && !isAdding,
            contentPadding = labelPadding,
            modifier = Modifier.weight(1f)
        ) { Text("動画を追加", maxLines = 1) }

        // 一時保存。文字を置くと左右のボタンの取り分が減るのでアイコンだけにする。
        // 形は他のアイコン専用ボタン（操作バー等）と揃えて正円にする。
        FilledTonalIconButton(
            onClick = onOpenSaves,
            // 読み込み中のタイムラインは途中の状態なので、保存も読み出しもさせない
            enabled = !isExporting && !isAdding,
            shape = CircleShape
        ) {
            Icon(
                VlogIcons.File,
                contentDescription = "編集内容の保存と読み出し",
                modifier = Modifier.size(TOOLBAR_ICON_SIZE)
            )
        }

        // 書き出し開始/終了の瞬間にボタンが入れ替わって見えないよう、フェードで橋渡しする
        AnimatedContent(
            targetState = isExporting,
            label = "exportOrCancelButton",
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f)
        ) { exporting ->
            if (exporting) {
                OutlinedButton(
                    onClick = onCancel,
                    contentPadding = labelPadding,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("中止", maxLines = 1) }
            } else {
                ExportButton(
                    enabled = canExport,
                    contentPadding = labelPadding,
                    onExport = onExport,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 書き出しボタン。タップ＝タイトルカードあり、長押し＝タイトルカードなしで書き出す。
 * 通常の[androidx.compose.material3.Button]は長押しを扱えないため、見た目だけ真似た
 * [Surface]を[combinedClickable]で組んでいる。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExportButton(
    enabled: Boolean,
    contentPadding: PaddingValues,
    onExport: (includeTitle: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 通常のButtonと違い自前でenabled色を出しているため、ここも切り替わりを補間する
    val containerColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        label = "exportButtonContainerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        label = "exportButtonContentColor"
    )
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
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(modifier = Modifier.padding(contentPadding), contentAlignment = Alignment.Center) {
            Text("書き出し", maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 動画を追加している間（メタデータを読んでいる間）の進捗。数秒かかることがあるため、何も出さないと固まって見える */
@Composable
private fun AddProgress(isAdding: Boolean) {
    AnimatedVisibility(
        visible = isAdding,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                text = "動画を読み込み中…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExportProgress(exportState: ExportState) {
    val running = exportState as? ExportState.Running
    val progress = running?.progress

    // 書き出し開始/終了でこのブロックごと瞬時に出入りせず、ふわっと現れる/消えるようにする
    AnimatedVisibility(
        visible = running != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            Spacer(Modifier.height(8.dp))
            // エンコード中は実際の割合を出す。割合が分からない工程（準備中・保存中）だけ
            // 不定形のバーにする。ここを常に不定形にしていた頃は、長い書き出しで
            // あと何割なのかが数字でしか分からなかった。
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                // 進捗が飛び飛び（1%刻み）に届くので、バーの伸びだけは補間して滑らかに見せる
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    label = "exportProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(4.dp))
            // メッセージ（工程の切り替わり）も差し替わる瞬間にチラつかせず、文字だけフェードする
            AnimatedContent(
                targetState = running?.message.orEmpty(),
                label = "exportProgressMessage",
                transitionSpec = { fadeIn() togetherWith fadeOut() }
            ) { message ->
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
