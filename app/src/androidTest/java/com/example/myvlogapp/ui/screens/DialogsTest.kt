package com.example.myvlogapp.ui.screens

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.myvlogapp.data.SavedProject
import com.example.myvlogapp.ui.theme.MyVlogAppTheme

/** 書き出しのタイトル作成・動画を選ぶ画面（許可が無いとき）・一時保存のダイアログの画面操作 */
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

    // --- 動画を選ぶ（許可が無いとき） ---------------------------------------------------

    @Test
    fun withoutMediaAccessTheGalleryOffersToAskAgainOrOpenSettings() {
        // 許可が無くてもギャラリーの画面は開き、許可し直す道を出す（以前は画面ごと開かず、
        // その中にしかない「ファイル」まで辿り着けなかった）
        var requested = 0
        var openedSettings = 0
        rule.setContent {
            MyVlogAppTheme {
                NoMediaAccess(onRequestAccess = { requested++ }, onOpenSettings = { openedSettings++ })
            }
        }

        rule.onNodeWithText("動画へのアクセスが許可されていないため、一覧を出せません").assertExists()
        rule.onNodeWithText("右上の「ファイル」から選んで追加することもできます").assertExists()
        rule.onNodeWithText("許可する").performClick()
        rule.onNodeWithText("設定を開く").performClick()
        rule.runOnIdle {
            assertEquals(1, requested)
            assertEquals(1, openedSettings)
        }
    }

    // --- 一時保存 ----------------------------------------------------------------------

    private val trip = SavedProject(id = 7L, name = "旅行", savedAt = 0L, clipCount = 3, totalMs = 9_000L)
    private val saved = mutableListOf<String>()
    private val overwritten = mutableListOf<Long>()
    private val deleted = mutableListOf<Long>()
    private var dismissed = false

    private fun showSaveLoadDialog(canSave: Boolean) {
        rule.setContent {
            MyVlogAppTheme {
                SaveLoadDialog(
                    projects = listOf(trip),
                    canSave = canSave,
                    onSave = { saved += it },
                    onLoad = {},
                    onOverwrite = { overwritten += it.id },
                    onDelete = { deleted += it },
                    onDismiss = { dismissed = true }
                )
            }
        }
    }

    @Test
    fun savingClosesTheDialog() {
        // 開いたままだと続けて押せて、同じ内容が2件保存されてしまう
        showSaveLoadDialog(canSave = true)

        rule.onNodeWithText("この内容を保存").performClick()

        rule.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(true, dismissed)
        }
    }

    @Test
    fun overwritingASaveByLongPressAsksForConfirmationFirst() {
        showSaveLoadDialog(canSave = true)

        // 上書きは「もとに戻す」で戻せないので、長押しだけでは上書きせずに確認を出す
        rule.onNodeWithText("旅行").performTouchInput { longClick() }
        rule.onNodeWithText("上書きしますか").assertExists()
        rule.runOnIdle { assertEquals(emptyList<Long>(), overwritten) }

        rule.onNodeWithText("上書き").performClick()
        rule.runOnIdle { assertEquals(listOf(7L), overwritten) }
    }

    @Test
    fun longPressDoesNotOfferOverwritingWhenNothingCanBeSaved() {
        // タイムラインが空などで保存できないときは、上書き（保存の一種）の確認も出さない。
        // 出すと、確認まで進んでから「保存できる編集内容がありません」と断ることになる
        showSaveLoadDialog(canSave = false)

        rule.onNodeWithText("旅行").performTouchInput { longClick() }
        rule.onNodeWithText("上書きしますか").assertDoesNotExist()
    }

    @Test
    fun deletingASaveAsksForConfirmationFirst() {
        showSaveLoadDialog(canSave = true)

        // 取り消せないので、押しただけでは消さずに確認を出す
        rule.onNodeWithContentDescription("「旅行」を削除").performClick()
        rule.onNodeWithText("削除しますか").assertExists()
        rule.runOnIdle { assertEquals(emptyList<Long>(), deleted) }

        rule.onNodeWithText("削除").performClick()
        rule.runOnIdle { assertEquals(listOf(7L), deleted) }
    }
}
