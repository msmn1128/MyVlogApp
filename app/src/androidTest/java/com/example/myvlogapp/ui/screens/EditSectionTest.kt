package com.example.myvlogapp.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.myvlogapp.TextSegment
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.ui.theme.MyVlogAppTheme
import com.example.myvlogapp.waveform.SelectedWaveform
import com.example.myvlogapp.waveform.WaveformTrimmerCallbacks

/**
 * タイムラインとひとこと入力のまとまり（[EditSection]）の画面操作。
 *
 * ViewModelの代わりに、状態は固定の値、操作は呼ばれた内容を記録するだけの偽物を渡し、
 * 「どの操作で何が呼ばれるか（呼ばれないか）」を確かめる。
 */
@RunWith(AndroidJUnit4::class)
class EditSectionTest {

    @get:Rule
    val rule = createComposeRule()

    /** 操作から呼ばれた内容の記録 */
    private class Calls {
        val texts = mutableListOf<String>()
        val timelineMuted = mutableListOf<Boolean>()
        val trims = mutableListOf<Triple<Long, Long, Long>>()
        val moves = mutableListOf<Pair<Long, Long>>()
        val splits = mutableListOf<Pair<Int, Long>>()
    }

    private val calls = Calls()

    /** 再生位置。画面の組み立ての外で作る（組み立ての中で作ると、組み立て直しのたびに作り直される） */
    private val positionMs = mutableLongStateOf(3_000L)

    /** 素材10秒のうち2〜8秒を使い、5秒でひとことを切り替えるクリップ */
    private fun clip(id: Long = 1L) = VlogClip(
        id = id,
        uri = Uri.parse("content://test/$id"),
        timeText = "10:00",
        dateText = "2026/09/20",
        durationMs = 10_000L,
        texts = listOf(TextSegment(0L, "前半"), TextSegment(5_000L, "後半")),
        startMs = 2_000L,
        endMs = 8_000L
    )

    private fun show(clips: List<VlogClip>, missingClipIds: Set<Long> = emptySet(), timelineMuted: Boolean = false) {
        val state = TimelineState(
            canUndo = MutableStateFlow(false),
            canRedo = MutableStateFlow(false),
            autoAdvance = MutableStateFlow(true),
            timelineMuted = MutableStateFlow(timelineMuted),
            selectedWaveform = MutableStateFlow(SelectedWaveform(waveform = null, isLoading = false)),
            replacementCount = MutableStateFlow(0),
            isPlaying = MutableStateFlow(false),
            missingClipIds = MutableStateFlow(missingClipIds)
        )
        val actions = TimelineActions(
            select = {}, removeSelected = {}, removeAll = {}, moveSelected = {}, toggleClipMute = {},
            setTimelineMuted = { calls.timelineMuted += it },
            setAutoAdvance = {}, undo = {}, redo = {}, applyTrimPreset = {},
            splitTextAtPlayhead = {}, removeSplit = {},
            updateText = { calls.texts += it },
            pause = {},
            trimmer = WaveformTrimmerCallbacks(
                onTrimChange = { s, e, p -> calls.trims += Triple(s, e, p) },
                onTrimMove = { s, p -> calls.moves += s to p },
                onSplitMove = { i, ms -> calls.splits += i to ms },
                onSeek = {}, onScrubStart = {}, onScrubEnd = {}, onDragStart = {}, onDragEnd = {}
            )
        )
        rule.setContent {
            MyVlogAppTheme {
                Column(Modifier.fillMaxSize()) {
                    EditSection(
                        clips = clips,
                        selectedIndex = 0,
                        selectedClip = clips.firstOrNull(),
                        positionMs = positionMs,
                        state = state,
                        actions = actions,
                        isExporting = false,
                        timelineWeight = 0.6f,
                        editorWeight = 0.4f,
                        isImeVisible = false,
                        showTimeline = true,
                        showEditorHeader = true,
                        timelineFit = TimelineFit()
                    )
                }
            }
        }
    }

    // --- ひとこと欄 --------------------------------------------------------------------

    @Test
    fun tappingOrMovingTheCursorDoesNotRewriteTheText_typingDoes() {
        show(listOf(clip()))
        val field = rule.onNode(hasSetTextAction())

        // タップしてカーソルを動かすだけでは、書き換えを伝えない（「もとに戻す」が積まれないように）
        field.performClick()
        field.performTextInputSelection(TextRange(0))
        field.performTextInputSelection(TextRange(1))
        rule.runOnIdle { assertEquals(emptyList<String>(), calls.texts) }

        // 文字を打ったら伝える
        field.performTextInput("旅")
        rule.runOnIdle { assertTrue(calls.texts.toString(), calls.texts.lastOrNull()?.contains("旅") == true) }
    }

    @Test
    fun theFieldFollowsTheSegmentUnderThePlayhead() {
        // 外から文字が変わったとき（区間の切り替わり・クリップの選択・もとに戻す）は、入力欄を入れ替える。
        // 自分で打った文字を巻き戻さないための仕組み（EditorPane）が、これまで止めてはいけない
        show(listOf(clip()))
        val field = rule.onNode(hasSetTextAction())
        field.assertTextEquals("前半", includeEditableText = true)

        rule.runOnIdle { positionMs.longValue = 6_000L }

        field.assertTextEquals("後半", includeEditableText = true)
        rule.runOnIdle { assertEquals(emptyList<String>(), calls.texts) }
    }

    // --- 操作バー ----------------------------------------------------------------------

    @Test
    fun timelineMuteIsASwitchThatReportsItsState() {
        show(listOf(clip()), timelineMuted = false)
        val mute = rule.onNodeWithContentDescription("タイムラインのミュート", substring = true)

        // TalkBackにスイッチの状態（オフ）として伝わり、押すと新しい状態（オン）が渡る
        mute.performScrollTo().assertIsOff()
        mute.performClick()
        rule.runOnIdle { assertEquals(listOf(true), calls.timelineMuted) }
    }

    @Test
    fun timelineMuteShowsOnWhenMuted() {
        show(listOf(clip()), timelineMuted = true)
        rule.onNodeWithContentDescription("タイムラインのミュート", substring = true).assertIsOn()
    }

    // --- タイル ------------------------------------------------------------------------

    @Test
    fun aMissingClipTileShowsTheWarning() {
        show(listOf(clip(id = 1L), clip(id = 2L)), missingClipIds = setOf(2L))
        rule.onNodeWithContentDescription("動画が見つかりません", substring = true).assertExists()
    }

    @Test
    fun noWarningWhenEveryClipIsReadable() {
        show(listOf(clip()))
        rule.onNodeWithContentDescription("動画が見つかりません", substring = true).assertDoesNotExist()
    }

    // --- 波形（読み上げ） ----------------------------------------------------------------

    @Test
    fun waveformDescribesTheRangeAndSplits() {
        show(listOf(clip()))
        val description = waveform().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.contains("0:02.0〜0:08.0"))
        assertTrue(description, description.contains("6.0秒"))
        assertTrue(description, description.contains("区切り 1か所（0:05.0）"))
    }

    @Test
    fun waveformActionsMoveTheTrimAndSplitsByHalfASecond() {
        show(listOf(clip()))

        waveform().invokeAction("開始を0.5秒後ろへ")
        waveform().invokeAction("終わりを0.5秒前へ")
        waveform().invokeAction("範囲ごと0.5秒前へ")
        waveform().invokeAction("区切り1を0.5秒後ろへ")

        rule.runOnIdle {
            // 開始・終わりは、動かした側の位置をプレビューに出す
            assertEquals(listOf(Triple(2_500L, 8_000L, 2_500L), Triple(2_000L, 7_500L, 7_500L)), calls.trims)
            assertEquals(listOf(1_500L to 1_500L), calls.moves)
            assertEquals(listOf(1 to 5_500L), calls.splits)
        }
    }

    private fun waveform() = rule.onNodeWithContentDescription("波形。", substring = true)

    /** 読み上げのアクション（TalkBackのアクション一覧の項目）を、名前で選んで実行する */
    private fun SemanticsNodeInteraction.invokeAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        rule.runOnIdle { action.action() }
    }
}
