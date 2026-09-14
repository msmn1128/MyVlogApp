package com.example.myvlogapp // ← ご自身のパッケージ名に合わせて変更してください

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// =====================================================================================
// MainActivity.kt から切り出した、タイムライン（クリップ一覧・波形トリマー・操作バー）と
// ひとこと入力欄のまとまり。
// =====================================================================================

/** タイムラインとひとこと入力のまとまり。分けない理由は [PreviewSection] と同じ */
@Composable
internal fun ColumnScope.EditSection(
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
                        fontSize = 12.sp,
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
                            onLongClick = { viewModel.toggleClipMute(clip.id) },
                            // 削除・追加・並べ替えで前後のタイルが瞬間移動せず、
                            // 新しい位置へ滑らかにスライドするようにする
                            modifier = Modifier.animateItem()
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
                    onScrubEnd = viewModel::endScrub,
                    onDragStart = viewModel::beginInteractiveSeek,
                    onDragEnd = viewModel::endInteractiveSeek
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
private fun ClipTile(
    clip: VlogClip,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 選択状態の切り替わりで色・枠線が一瞬で変わらず、じわっと変化するようにする
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "clipTileContainerColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "clipTileBorderColor"
    )
    Surface(
        // 高さも幅も中身に任せる（最小幅だけ104dp）。固定にすると、端末の文字サイズ設定を
        // 上げたときや区間バッジが付いたときに、尺の行がタイルの丸角からはみ出して
        // 切れて見える（＝枠の右下だけ幅が変わったように見える）
        modifier = modifier
            .widthIn(min = 104.dp)
            .combinedClickable(
                onClickLabel = "選択",
                onLongClickLabel = if (clip.isMuted) "ミュートを解除" else "ミュート",
                onLongClick = onLongClick,
                onClick = onClick
            ),
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        // 未選択にも枠を付ける。カードと明度が近く、無地だと
        // どこまでが1クリップなのか輪郭が見えないため
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
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
            // 途中で切り替わる場合も、タイルには頭に出る文字を載せる。
            // タイル自体の幅は区間バッジぶんに合わせて伸びられるようにしたが、
            // この行だけは幅の基準（104dpから左右の padding を引いた分）に固定して、
            // 長いひとことでタイルごと際限なく横に伸びないようにする
            Text(
                clip.textAt(clip.startMs).ifBlank { DEFAULT_HITOKOTO },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 104.dp - 12.dp)
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
                // タイルを見ただけで「途中で文字が変わる」と分かる。
                //
                // AnimatedVisibilityで出し入れせず、常にレイアウトへ含めて
                // 透明度だけを変える。高さ・幅を条件で増減させると、
                // 端末の文字サイズ設定によってはバッジの実サイズを固定値で
                // 見積もりきれず、分割した瞬間にタイルの枠自体が動いて見える。
                // 常に場所を確保しておけば、分割前から最終サイズになっている
                val segmentBadgeAlpha by animateFloatAsState(
                    targetValue = if (clip.texts.size > 1) 1f else 0f,
                    label = "clipTileSegmentBadgeAlpha"
                )
                Box(modifier = Modifier.alpha(segmentBadgeAlpha)) {
                    SegmentBadge(
                        "1-${clip.texts.size}",
                        fontSize = 9.sp,
                        horizontalPadding = 4.dp
                    )
                }
                // ミュート中のクリップは長押ししないと気付けないので、常時アイコンで示す
                AnimatedVisibility(
                    visible = clip.isMuted,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("ひとこと", style = MaterialTheme.typography.titleSmall)
                // 分割しているときだけ、いま何番目を触っているのかを出す。
                //
                // AnimatedVisibilityで出し入れせず、常にレイアウトへ含めて
                // 透明度だけを変える。端末の文字サイズ設定によってはバッジの
                // 実サイズを固定値で見積もりきれず、分割した瞬間に「ひとこと」欄の
                // 枠自体が動いて見えるため、常に場所を確保しておく
                val segmentInfoAlpha by animateFloatAsState(
                    targetValue = if (segmentCount > 1) 1f else 0f,
                    label = "editorPaneSegmentInfoAlpha"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.alpha(segmentInfoAlpha)
                ) {
                    SegmentBadge("$segmentNumber", fontSize = 9.sp, horizontalPadding = 4.dp)
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
