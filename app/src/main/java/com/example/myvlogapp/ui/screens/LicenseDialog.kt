package com.example.myvlogapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** APKに同梱しているGPLv3の全文（gnu.orgのgpl-3.0.txtそのまま） */
private const val GPL_TEXT_ASSET = "licenses/GPL-3.0.txt"

/**
 * ライセンスの表示。
 *
 * 同梱のフォントの著作権表示も載せる（それぞれのフォントファイルの中の表記と同じ）。
 * タイトルの効果音（assets/sfx/title.mp3）は表記の要らない素材なので載せていない。
 *
 * 書き出しに使っているFFmpeg（ffmpeg-kit）は`--enable-gpl --enable-version3`でビルドされて
 * おり、それを含むこのAPKはGPLv3の条件で配布している。GPLv3は画面を持つプログラムに、
 * 著作権表示・無保証であること・この条件で再配布できること・全文の見方を画面から
 * 見られるようにすることを求める（第0条「Appropriate Legal Notices」、第5条(d)）。
 * その入口として保存ダイアログの下端から開く。
 *
 * アプリ自身のソースコードはMITで公開しているが、FFmpegと組み合わせたAPKとしてはGPLv3になる。
 * 文面を変えるときは README の「ライセンス」節も合わせること。
 */
@Composable
internal fun LicenseDialog(onDismiss: () -> Unit) {
    var showFullText by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ライセンス") },
        text = {
            // URLを長押しでコピーできるようにする（リンクを開く仕組みは持たせていない）
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(NOTICE, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (showFullText) {
                        GplFullText()
                    } else {
                        TextButton(onClick = { showFullText = true }) {
                            Text("GPLv3 の全文を表示")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

/** 全文は約35KBあるので、開いたときだけ読む */
@Composable
private fun GplFullText() {
    val context = LocalContext.current
    val text by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(GPL_TEXT_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }
    Text(
        text ?: "読み込み中…",
        // 原文は固定幅で折り返されているので、等幅で出すと崩れにくい
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 12.sp
    )
}

private val NOTICE = """
MyVlog.
Copyright (c) 2026 msmn1128

このアプリは、書き出しに FFmpeg（ffmpeg-kit 6.1.1 に同梱のもの）を使っています。この FFmpeg は GPL を有効にしてビルドされているため、このアプリ（APK）全体は GNU General Public License バージョン3（またはそれ以降の版）の条件で配布しています。この条件に従って、再配布・改変ができます。アプリ自身のソースコードは MIT ライセンスで公開しています。

このアプリは無保証です。商品性や特定の目的への適合性の保証を含め、いかなる保証もありません。詳しくは GPLv3 の全文をご覧ください。

ソースコード
・アプリ：https://github.com/msmn1128/MyVlogApp
・FFmpeg（ffmpeg-kit、ビルド用スクリプトを含む）：https://github.com/moizhassankh/ffmpeg-kit-android-16KB （タグ 6.1.1）
・FFmpeg 本体：https://github.com/arthenica/FFmpeg （タグ n6.0）
・上の2つの控えは、アプリの配布ページ（https://github.com/msmn1128/MyVlogApp/releases）にも添付しています

GPLv3 の全文：https://www.gnu.org/licenses/gpl-3.0.html

フォント
・M PLUS U（撮影時刻・タイトルの文言）：Copyright 2025 The M+ FONTS Project Authors（https://github.com/coz-m/MPLUS_FONTS）。SIL Open Font License, Version 1.1（https://openfontlicense.org）
・07ロゴたいぷゴシック7（ひとこと・「Vlog.」）：Copyright (c) 2013 M+ FONTS PROJECT／フォントな（www.fontna.com）
""".trim()
