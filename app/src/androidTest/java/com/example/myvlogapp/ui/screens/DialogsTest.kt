package com.example.myvlogapp.ui.screens

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.myvlogapp.data.SavedProject
import com.example.myvlogapp.ui.theme.MyVlogAppTheme

/** 書き出しのタイトル作成と、一時保存のダイアログの画面操作 */
@RunWith(AndroidJUnit4::class)
class DialogsTest {

    @get:Rule
    val rule = createComposeRule()

    // --- タイトル作成 -------------------------------------------------------------------

    private var confirmedTitle: String? = null

    private fun showTitleDialog() {
        rule.setContent {
            MyVlogAppTheme {
                TitleCreationDialog(
                    defaultText = "2026/09/20",
                    timeFontFamily = FontFamily.Default,
                    onDismiss = {},
                    onConfirm = { confirmedTitle = it }
                )
            }
        }
    }

    @Test
    fun titleDefaultsToTheShootingDate() {
        showTitleDialog()
        rule.onNodeWithText("書き出し").performClick()
        rule.runOnIdle { assertEquals("2026/09/20", confirmedTitle) }
    }

    @Test
    fun typedTitleIsUsedInsteadOfTheDate() {
        showTitleDialog()
        rule.onNode(hasSetTextAction()).performTextInput("夏の旅行")
        rule.onNodeWithText("書き出し").performClick()
        rule.runOnIdle { assertEquals("夏の旅行", confirmedTitle) }
    }

    // --- 一時保存 ----------------------------------------------------------------------

    @Test
    fun deletingASaveAsksForConfirmationFirst() {
        val deleted = mutableListOf<Long>()
        rule.setContent {
            MyVlogAppTheme {
                SaveLoadDialog(
                    projects = listOf(SavedProject(id = 7L, name = "旅行", savedAt = 0L, clipCount = 3, totalMs = 9_000L)),
                    canSave = true,
                    onSave = {},
                    onLoad = {},
                    onOverwrite = {},
                    onDelete = { deleted += it },
                    onDismiss = {}
                )
            }
        }

        // 取り消せないので、押しただけでは消さずに確認を出す
        rule.onNodeWithContentDescription("「旅行」を削除").performClick()
        rule.onNodeWithText("削除しますか").assertExists()
        rule.runOnIdle { assertEquals(emptyList<Long>(), deleted) }

        rule.onNodeWithText("削除").performClick()
        rule.runOnIdle { assertEquals(listOf(7L), deleted) }
    }
}
