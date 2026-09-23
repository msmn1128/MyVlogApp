package com.example.myvlogapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

// =====================================================================================
// 別の画面（システムの許可の画面・ファイル選択）を開く入口。MainActivity.kt から切り出したもの。
// どちらも呼ぶのは VlogAppScreen の、縦横の分岐より外側（理由は VlogAppScreen の呼び出し箇所）。
// =====================================================================================

/**
 * 書き出しを始める入口を作る。返す関数の引数は（タイトルを付けるか, タイトルの文言）。
 * 文言はタイトルを付けるとき（タイトル作成ダイアログで確定済み）だけ意味を持つ。
 *
 * 書き出し中はフォアグラウンドサービスの通知を出す（VlogExportService）。Android 13以降は
 * 表示に実行時の許可が要るので、まだ許可されていなければ先に聞き、答えが返ってから始める。
 * 許可されてもされなくても書き出す（通知は進み具合を知らせるだけで、無くても書き出せる）。
 * 聞くのと同時に始めていた頃は、許可の画面の裏で書き出しが進み、短い書き出しだと
 * 完了の知らせまで画面の裏で済んで、何が起きたか分からなかった。
 * 2回断られるとシステムはもう画面を出さず、すぐに「拒否」で返ってくるので、その場合もすぐ始まる。
 *
 * 呼ぶのは[VlogAppScreen]の、縦横の分岐より外側（理由は呼び出し側）。
 */
@Composable
internal fun rememberExportStarter(
    onStart: (includeTitle: Boolean, titleText: String?) -> Unit
): (includeTitle: Boolean, titleText: String?) -> Unit {
    val context = LocalContext.current
    // 許可を聞いている間、始める書き出しの内容を覚えておく。許可の画面は別のActivityで、
    // 答えるまでの間にこの画面がメモリ不足で回収されることがある（回転では作り直さない設定。
    // マニフェストのconfigChanges）。戻ったときに消えていないようrememberSaveableにする
    var pending by rememberSaveable { mutableStateOf(false) }
    var pendingIncludeTitle by rememberSaveable { mutableStateOf(false) }
    var pendingTitleText by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pending) {
            pending = false
            onStart(pendingIncludeTitle, pendingTitleText)
        }
    }
    return { includeTitle, titleText ->
        val needsToAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsToAsk) {
            pendingIncludeTitle = includeTitle
            pendingTitleText = titleText
            pending = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onStart(includeTitle, titleText)
        }
    }
}

/**
 * 「最初に開く場所」を指定できるようにしたドキュメントピッカー。
 *
 * 既定では「最近使用したファイル」が開き、撮影した動画に辿り着くまでに
 * ドロワーを開いて階層を降りる必要がある。カメラの保存先を初期表示にして
 * その手間を無くす。
 *
 * 指定先が存在しない端末ではEXTRA_INITIAL_URIが単に無視され、従来どおりの画面が
 * 開くだけなので、フォールバックは要らない。
 */
internal class OpenVideosFromCamera : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:DCIM/Camera"
                )
            )
        }
}
