package com.example.myvlogapp.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.font.FontFamily
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.export.ExportState
import com.example.myvlogapp.ui.theme.MyVlogAppTheme

/** プレビューの画面操作（再生そのものは確かめない。ExoPlayerの実挙動は実機で見る） */
@RunWith(AndroidJUnit4::class)
class PreviewSectionTest {

    @get:Rule
    val rule = createComposeRule()

    // ExoPlayerはLooperのあるスレッドで作る必要があるので、メインスレッドで作る
    private lateinit var player: ExoPlayer

    @Before
    fun createPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { player = ExoPlayer.Builder(instrumentation.targetContext).build() }
    }

    @After
    fun releasePlayer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
    }

    @Test
    fun previewTellsScreenReadersThatTappingPlaysOrPauses() {
        // プレビューのタップで再生／一時停止するが、以前は読み上げ（TalkBack）に何の操作か伝わらなかった
        var toggled = 0
        val clip = VlogClip(
            id = 1L, uri = Uri.parse("content://media/external/video/media/1"),
            timeText = "10:30", dateText = "2026/09/24", durationMs = 3_000L, endMs = 3_000L
        )
        val position = mutableLongStateOf(0L)
        rule.setContent {
            MyVlogAppTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    PreviewSection(
                        selectedClip = clip,
                        player = player,
                        hitokotoFontFamily = FontFamily.Default,
                        timeFontFamily = FontFamily.Default,
                        positionMs = position,
                        exportState = ExportState.Idle,
                        isExporting = false,
                        isAdding = false,
                        canExport = true,
                        previewWeight = 1f,
                        onTogglePlayback = { toggled++ },
                        onAdd = {},
                        onOpenSaves = {},
                        onExport = {},
                        onCancelExport = {}
                    )
                }
            }
        }

        val preview = rule.onNode(
            SemanticsMatcher("「再生／一時停止」のボタン") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "再生／一時停止" &&
                    node.config.getOrNull(SemanticsProperties.Role) == Role.Button
            }
        )
        preview.performSemanticsAction(SemanticsActions.OnClick)
        rule.runOnIdle { assertEquals(1, toggled) }
    }
}
