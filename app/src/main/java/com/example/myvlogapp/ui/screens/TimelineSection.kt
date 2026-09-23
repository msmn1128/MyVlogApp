package com.example.myvlogapp.ui.screens

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myvlogapp.DEFAULT_HITOKOTO
import com.example.myvlogapp.SECTION_GAP
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.WAVEFORM_HEIGHT
import com.example.myvlogapp.formatSeconds
import com.example.myvlogapp.ui.components.CompactIconButton
import com.example.myvlogapp.ui.components.SegmentBadge
import com.example.myvlogapp.ui.components.TimelineDivider
import com.example.myvlogapp.ui.components.TimelineToggleButton
import com.example.myvlogapp.ui.components.TrimPresetButton
import com.example.myvlogapp.ui.components.VlogIcons
import com.example.myvlogapp.ui.components.splitMarkerColor
import com.example.myvlogapp.waveform.MIN_TRIM_MS
import com.example.myvlogapp.waveform.SelectedWaveform
import com.example.myvlogapp.waveform.WaveformTrimmer
import com.example.myvlogapp.waveform.WaveformTrimmerCallbacks
import kotlinx.coroutines.flow.StateFlow

// =====================================================================================
// MainActivity.kt から切り出した、タイムライン（クリップ一覧・波形トリマー・操作バー）と
// ひとこと入力欄のまとまり。
// =====================================================================================

/** タイムラインとひとこと入力のまとまり。分けない理由は [PreviewSection] と同じ */
@Composable
internal fun ColumnScope.EditSection(
    clips: List<VlogClip>,
    selectedIndex: Int,
    selectedClip: VlogClip?,
    positionMs: State<Long>,
    state: TimelineState,
    actions: TimelineActions,
    isExporting: Boolean,
    timelineWeight: Float,
    editorWeight: Float,
    isImeVisible: Boolean,
    showTimeline: Boolean
) {
    // 縦に短い画面でキーボードを出している間は、タイムラインを畳んでひとこと欄だけにする
    // （理由は呼び出し元のVlogAppScreen）。ひとこと欄の見出しも同じときに畳む
    if (showTimeline) {
        TimelinePane(
            clips = clips,
            selectedIndex = selectedIndex,
            selectedClip = selectedClip,
            positionMs = positionMs,
            state = state,
            actions = actions,
            isExporting = isExporting,
            modifier = Modifier.fillMaxWidth().weight(timelineWeight)
        )
        Spacer(Modifier.height(SECTION_GAP))
    }
    EditorPane(
        selectedClip = selectedClip,
        positionMs = positionMs,
        isExporting = isExporting,
        isImeVisible = isImeVisible,
        isPlaying = state.isPlaying,
        onTextChange = actions.updateText,
        onPause = actions.pause,
        showHeader = showTimeline,
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
    clips: List<VlogClip>,
    selectedIndex: Int,
    selectedClip: VlogClip?,
    positionMs: State<Long>,
    state: TimelineState,
    actions: TimelineActions,
    isExporting: Boolean,
    modifier: Modifier = Modifier
) {
    val canUndo by state.canUndo.collectAsStateWithLifecycle()
    val canRedo by state.canRedo.collectAsStateWithLifecycle()
    val autoAdvance by state.autoAdvance.collectAsStateWithLifecycle()
    val timelineMuted by state.timelineMuted.collectAsStateWithLifecycle()
    val replacementCount by state.replacementCount.collectAsStateWithLifecycle()
    // 波形はここでは読まない。1本届くたびにこのPane全体が再コンポーズされてしまうので、
    // 実際に使う[TrimSection]の中でだけcollectする。取得を始めるのも
    // ViewModel側（選択の変化を見ている）に移してある。

    // 選択中のタイルが常に見えるようにする。連続再生の自動遷移や「ひとつ後ろへ移動」で
    // 選択が変わっても、本数が多いとタイルが画面外のままになり、いまどれを編集して
    // いるのかタイムラインから読み取れなくなるため。
    val clipListState = rememberLazyListState()
    LaunchedEffect(selectedIndex, clips.size) {
        if (selectedIndex in clips.indices) clipListState.animateScrollToItem(selectedIndex)
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
                clips = clips,
                selectedIndex = selectedIndex,
                selectedClip = selectedClip,
                positionMs = positionMs,
                autoAdvance = autoAdvance,
                timelineMuted = timelineMuted,
                canUndo = canUndo,
                canRedo = canRedo,
                isExporting = isExporting,
                actions = actions
            )

            // 全削除のときもタイルが瞬時に消えず1件ずつと同じようにフェードアウトするよう、
            // 空になってもLazyRow自体は消さない（条件で囲むと、最後の1件が消える
            // アニメーションの途中でLazyRowごと引っ込んで打ち切られていた）。
            //
            // 丸ごとの入れ替え（一時保存の読み出しとその取り消し）のときだけは、逆に
            // LazyRowごと作り直す。全クリップのidが一斉に変わると、消えるタイルの
            // アニメーションが取り残されて画面に残り続けるため（[TimelineState.replacementCount]）。
            // スクロール位置はkeyの外で覚えているclipListStateが持つので、作り直しても飛ばない。
            key(replacementCount) {
                LazyRow(
                    state = clipListState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(clips, key = { _, clip -> clip.id }) { index, clip ->
                        ClipTile(
                            clip = clip,
                            isSelected = index == selectedIndex,
                            onClick = { actions.select(index) },
                            onLongClick = { actions.toggleClipMute(clip.id) },
                            // 削除・追加・並べ替えで前後のタイルが瞬間移動せず、
                            // 新しい位置へ滑らかにスライドするようにする
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            selectedClip?.let { clip ->
                Spacer(Modifier.height(10.dp))

                TrimSection(
                    clip = clip,
                    selectedWaveform = state.selectedWaveform,
                    positionMs = positionMs,
                    isExporting = isExporting,
                    trimmer = actions.trimmer
                )
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
    selectedWaveform: StateFlow<SelectedWaveform>,
    positionMs: State<Long>,
    isExporting: Boolean,
    trimmer: WaveformTrimmerCallbacks
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

            // 波形をcollectするのはここだけ。デコードが終わって届いたときに
            // 再コンポーズされるのも、この下のWaveformTrimmerだけで済む。
            val waveform by selectedWaveform.collectAsStateWithLifecycle()

            WaveformTrimmer(
                clipId = clip.id,
                waveform = waveform.waveform,
                isLoading = waveform.isLoading,
                texts = clip.texts,
                durationMs = clip.durationMs,
                startMs = clip.startMs,
                endMs = clip.endMs,
                positionMs = positionMs,
                enabled = !isExporting,
                callbacks = trimmer,
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
    clips: List<VlogClip>,
    selectedIndex: Int,
    selectedClip: VlogClip?,
    positionMs: State<Long>,
    autoAdvance: Boolean,
    timelineMuted: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isExporting: Boolean,
    actions: TimelineActions
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
        val trimPresetEnabled = enabled && selectedClip.durationMs > 0L

        // 並びは 削除 → 入れ替え → 連続再生 → もとに戻す → やり直す
        //        → 2s/4sプリセット → ひとことを分割
        // よく使う2s/4sプリセットとひとこと分割を右端に、誤タップが怖い
        // 削除系は逆に左端に置いて、頻用操作を巻き込まないようにしている。

        // タップ＝選択中のクリップだけ削除、長押し＝すべて削除。
        // どちらも押し間違えたら「もとに戻す」で復帰できるので、確認ダイアログは出さない
        CompactIconButton(
            icon = VlogIcons.Delete,
            contentDescription = "選択中のクリップを削除",
            enabled = enabled,
            onClick = actions.removeSelected,
            onLongClick = actions.removeAll,
            onLongClickLabel = "すべて削除",
            tint = MaterialTheme.colorScheme.error
        )

        TimelineDivider()

        CompactIconButton(
            icon = VlogIcons.MoveLeft,
            contentDescription = "ひとつ前へ移動",
            enabled = enabled && selectedIndex > 0,
            onClick = { actions.moveSelected(-1) }
        )
        CompactIconButton(
            icon = VlogIcons.MoveRight,
            contentDescription = "ひとつ後ろへ移動",
            enabled = enabled && selectedIndex < clips.lastIndex,
            onClick = { actions.moveSelected(1) }
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
            onClick = { actions.setTimelineMuted(!timelineMuted) }
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
            onClick = { actions.setAutoAdvance(!autoAdvance) }
        )

        TimelineDivider()

        CompactIconButton(
            icon = VlogIcons.Undo,
            contentDescription = "もとに戻す",
            enabled = canUndo && !isExporting,
            onClick = actions.undo
        )
        CompactIconButton(
            icon = VlogIcons.Redo,
            contentDescription = "やり直す",
            enabled = canRedo && !isExporting,
            onClick = actions.redo
        )

        TimelineDivider()

        // 選択範囲の始まりを起点にする（先頭からではない。理由はTimelineStore.applyTrimPreset）。
        // 読み上げのラベルも動作に合わせる
        TrimPresetButton(
            label = "2s",
            contentDescription = "選択範囲の始まりから2秒にする",
            enabled = trimPresetEnabled,
            onClick = { actions.applyTrimPreset(2_000L) }
        )
        TrimPresetButton(
            label = "4s",
            contentDescription = "選択範囲の始まりから4秒にする",
            enabled = trimPresetEnabled,
            onClick = { actions.applyTrimPreset(4_000L) }
        )

        TimelineDivider()

        // 再生ヘッドが区切りの上にあるときは、同じボタンが解除に変わる。
        // 区切りを消す手段が「もとに戻す」しか無いと、あとから直せなくなるため。
        // 再生位置そのものではなく「区切りの上にいるか」だけを読む（約80msごとの再コンポーズを避ける）
        val splitOnPlayhead by remember(selectedClip) {
            derivedStateOf { selectedClip?.splitPointNear(positionMs.value) }
        }
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
                val split = splitOnPlayhead
                if (split == null) actions.splitTextAtPlayhead()
                else actions.removeSplit(split)
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
    positionMs: State<Long>,
    isExporting: Boolean,
    isImeVisible: Boolean,
    isPlaying: StateFlow<Boolean>,
    onTextChange: (String) -> Unit,
    onPause: () -> Unit,
    showHeader: Boolean,
    modifier: Modifier = Modifier
) {
    val segmentCount = selectedClip?.texts?.size ?: 1
    // 再生位置そのものは読まず、区間番号とその区間の文字だけを派生させる。
    // 約80msごとにこの入力欄ごと再コンポーズされるのを避けるため。
    val segmentNumber by remember(selectedClip) {
        derivedStateOf { (selectedClip?.textIndexAt(positionMs.value) ?: 0) + 1 }
    }
    val hitokoto by remember(selectedClip) {
        derivedStateOf { selectedClip?.textAt(positionMs.value) ?: "" }
    }

    // 入力欄の文字とカーソル位置は、この場のstateとして持つ。
    //
    // ViewModelへ流した文字がStateFlowを一巡して戻ってきたものをvalueに入れていた頃は、
    // 日本語IMEの変換中に「未確定の文字」ごと外から差し替えられ、変換が勝手に確定したり
    // カーソルが末尾へ飛んだりした。ここで持てば、打っている最中に外から入れ替わることは
    // 無くなる（ViewModelへは打つたびに通知するので、保存やプレビューの反映は変わらない）。
    var field by remember { mutableStateOf(TextFieldValue()) }
    // 外から文字が変わったとき（区間の切り替わり・クリップの選択変更・もとに戻す）だけ
    // 入れ替える。自分が打った文字は一巡して同じ値で戻ってくるので、ここは素通りする。
    LaunchedEffect(selectedClip?.id, segmentNumber, hitokoto) {
        if (field.text != hitokoto) {
            field = TextFieldValue(hitokoto, TextRange(hitokoto.length))
        }
    }

    val focusManager = LocalFocusManager.current
    // キーボードを閉じたら、入力欄からフォーカスも外す。isFocusedだけで出し分けていると、
    // 閉じたあとも内部的にはフォーカスが残ったままで、空欄でもplaceholderが出ずカーソルだけ
    // 点滅し続ける（「動画追加時」の見た目と揃わない）。カーソル位置も先頭へ戻しておく。
    // 複数行入力した状態でキーボードを閉じると、閉じる直前のカーソル位置に内部スクロールが
    // 追従したまま止まり、行の途中で切れた表示になって残るため
    // （singleLineで1行に固定する対処も試したが、改行そのものが失われてしまうため採らない）。
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            focusManager.clearFocus()
            if (field.selection != TextRange.Zero) {
                field = field.copy(selection = TextRange.Zero)
            }
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 縦に短い画面でキーボードを出している間は見出しを畳む。見出しのぶんだけでも、
            // 入力欄が1行ぶんの高さ（上下の余白込み）に届かず、打った文字の下半分が切れていた
            if (showHeader) {
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
            }
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val playing by isPlaying.collectAsStateWithLifecycle()
            // 打っている間は再生させない。書き換える区間は再生位置で決まるので、再生中に打つと
            // 区間が切り替わった瞬間のキー入力が隣の区間へ前の区間の文字ごと書き込まれ、
            // 変換途中の文字も区間が変わるたびに外から差し替えられて勝手に確定してしまう。
            // 入力欄に触れたら止め、逆にキーボードを出したままプレビューをタップして
            // 再生を始めたら入力欄から抜ける（どちらか片方だけだと、もう片方の順で同じ状態に戻る）。
            LaunchedEffect(isFocused) {
                if (isFocused) onPause()
            }
            LaunchedEffect(playing) {
                if (playing) focusManager.clearFocus()
            }
            val fieldTextStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center)
            val density = LocalDensity.current
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // OutlinedTextFieldの高さをweight(1f)のまま（＝親から渡された高さぴったり）にすると、
                // IMEを閉じているときの取り分が行の高さの整数倍にならず、内部スクロールが
                // 行の途中で止まって上下が欠けて見える。行高の整数倍に切り詰めた高さを
                // 明示することで、常に1行は丸ごと見せる（欠けた行はスクロールへ回す）。
                val contentPadding = OutlinedTextFieldDefaults.contentPadding()
                val verticalPadding =
                    contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()
                val lineHeight = with(density) { fieldTextStyle.lineHeight.toDp() }
                val lines = maxOf(1, ((maxHeight - verticalPadding) / lineHeight).toInt())
                // M3のOutlinedTextFieldは文字・placeholderの領域に最低MinTextLineHeight（24dp、
                // material3 1.4.0のTextFieldImpl.ktにある非公開の定数）を heightIn(min) で要求する。
                // bodyMediumのlineHeight（20sp≈20dp）で1行ぶんの箱を作ると4dp足りず、グレーの
                // placeholder文字がわずかに上下で見切れる。ただしこれは領域全体の下限であって
                // 1行あたりの高さではない。行数に24dpを掛けると2行以上で1行4dpずつ余り、
                // その余りで次の行が途中まで覗いて見切れが戻る。非公開の値なので
                // material3のバージョンを上げたときはこの24dpがまだ合っているか確認すること。
                val textAreaHeight = maxOf(lineHeight * lines, 24.dp)
                // 1行ぶん（verticalPadding + textAreaHeight）が親から渡されたmaxHeightより大きくなる
                // ことがある（横向きやマルチウィンドウでeditorWeightの取り分が小さいとき）。
                // その場合でもcoerceAtMostで実際の高さに収め、親のweight制約で暗黙に縮められて
                // レイアウトが食い違うのを防ぐ（縮められた結果また見切れが起きるのは避けられないが、
                // 高さの要求自体は矛盾しないようにしておく）。
                val fieldHeight = (verticalPadding + textAreaHeight).coerceAtMost(maxHeight)
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        // カーソル移動や変換範囲の変化だけでも呼ばれる。文字が変わったときだけ流す
                        val textChanged = it.text != field.text
                        field = it
                        if (textChanged) onTextChange(it.text)
                    },
                    enabled = selectedClip != null && !isExporting,
                    // 未入力かつ未フォーカスのときだけ「ひとこと」をグレーで案内表示する。
                    // M3のplaceholderは中身の空文字判定しか見ずフォーカスを見ないので、
                    // タップした瞬間（打ち始める前）に消したいならここで自分で出し分ける。
                    // これは入力欄の見た目だけで、実際の値は空文字のまま
                    // （プレビュー・書き出しには何も焼き込まれない）
                    placeholder = if (isFocused) null else {
                        {
                            // M3のOutlinedTextFieldはplaceholderの文字サイズをtextStyle引数と関係なく
                            // 常にbodyLargeで描く実装のため、ここでstyleを明示して入力文字と揃える。
                            // fillMaxWidth()を付けないとtextAlign=Centerが文字幅の中でしか効かず
                            // 見た目には左寄せのまま変わらない。
                            Text(
                                DEFAULT_HITOKOTO,
                                style = fieldTextStyle,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    textStyle = fieldTextStyle,
                    interactionSource = interactionSource,
                    // singleLineにはしない。改行そのものを許さないと、複数区間ぶんの長い
                    // ひとことを改行で見やすく整えられなくなる。
                    // imeActionを明示的にNoneにしておく。指定しないとDefaultになり、IME側の
                    // 判断でエンターキーが「確定」扱いになって閉じてしまう環境がある
                    // （改行として入らない）。書き出し側（ExportTextFiles）は"\n"で行を
                    // 分けてdrawtextを積むので、改行はそのまま複数行として焼き込まれる。
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    modifier = Modifier.fillMaxWidth().height(fieldHeight)
                )
            }
        }
    }
}
