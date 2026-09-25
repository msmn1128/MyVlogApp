package com.example.myvlogapp.ui.screens

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.myvlogapp.VlogClip
import com.example.myvlogapp.ui.theme.MyVlogAppTheme

/** タイムラインのクリップのタイル（タップで選択・長押しでミュート） */
@RunWith(AndroidJUnit4::class)
class ClipTileTest {

    @get:Rule
    val rule = createComposeRule()

    private val clip = VlogClip(
        id = 1L, uri = Uri.parse("content://media/external/video/media/1"),
        timeText = "10:30", dateText = "2026/09/25", durationMs = 3_000L, endMs = 3_000L
    )

    private val hasLongClickAction = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    @Test
    fun longPressTogglesMute() {
        var toggled = 0
        rule.setContent {
            MyVlogAppTheme {
                ClipTile(clip = clip, isSelected = false, isMissing = false, onClick = {}, onLongClick = { toggled++ })
            }
        }
        rule.onNode(hasLongClickAction).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, toggled)
    }

    @Test
    fun longPressIsNotOfferedWhileExporting() {
        // 書き出し中は、ほかの編集と同じくミュートも切り替えさせない。書き出すのは押した時点の内容なので、
        // 切り替えても出来上がる動画には入らず、画面と動画が食い違って見えていた。
        // 読み上げ（TalkBack）の「ミュート」の操作も出さない。選ぶ（タップ）ことはできる
        rule.setContent {
            MyVlogAppTheme {
                ClipTile(clip = clip, isSelected = false, isMissing = false, onClick = {}, onLongClick = null)
            }
        }
        rule.onAllNodes(hasLongClickAction).assertCountEquals(0)
        rule.onNode(hasClickAction()).assertExists()
    }
}
