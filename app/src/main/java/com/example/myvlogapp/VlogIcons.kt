package com.example.myvlogapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * タイムライン操作バーのアイコン。
 *
 * material-icons-extended を足せば undo / redo / delete_sweep は既製品で揃うが、
 * あれは3万個ぶんのImageVectorを含む巨大な依存で、R8を切っている現状では
 * 使わないアイコンまで丸ごとAPKに載ってしまう。必要なぶんだけをここに置く。
 *
 * パスはMaterial Iconsの24dp版と同じ形。塗りを白にしてあるのは、
 * Iconコンポーザブルが上からtintを掛けて配色に合わせるため（色は呼び出し側が決める）。
 */
private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.White)
    ).build()

object VlogIcons {

    /** 連続再生のオン/オフ */
    val Play: ImageVector by lazy {
        materialIcon("Play", "M8 5v14l11-7z")
    }

    /** 選択中のクリップをひとつ前へ */
    val MoveLeft: ImageVector by lazy {
        materialIcon(
            "MoveLeft",
            "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
        )
    }

    /** 選択中のクリップをひとつ後ろへ */
    val MoveRight: ImageVector by lazy {
        materialIcon(
            "MoveRight",
            "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z"
        )
    }

    /** もとに戻す（左へ曲がる矢印） */
    val Undo: ImageVector by lazy {
        materialIcon(
            "Undo",
            "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 " +
                "5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z"
        )
    }

    /** やり直す（右へ曲がる矢印） */
    val Redo: ImageVector by lazy {
        materialIcon(
            "Redo",
            "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 " +
                "16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.85L13 16h9V7l-3.6 3.6z"
        )
    }

    /**
     * ひとことをここで分割（吹き出し＋プラス）。
     *
     * 吹き出しの尻尾（右下の三角）を無視した「四角い本体」は x:2〜22, y:2〜18 で、
     * 中心は(12,10)。プラス記号は本体の中でこの(12,10)を中心に置く
     * （24x24全体の幾何中心(12,12)に置くと、尻尾ぶん本体の下寄りになって見える）。
     */
    val SplitText: ImageVector by lazy {
        materialIcon(
            "SplitText",
            "M22 4c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4V4zm-6 " +
                "7h-3v3h-2v-3H8v-2h3V6h2v3h3v2z"
        )
    }

    /** ひとことの区切りを解除（吹き出し＋マイナス）。中心の考え方は[SplitText]参照 */
    val SplitTextOff: ImageVector by lazy {
        materialIcon(
            "SplitTextOff",
            "M22 4c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4V4zM16 " +
                "11H8v-2h8v2z"
        )
    }

    /** 一時保存（ファイル） */
    val File: ImageVector by lazy {
        materialIcon(
            "File",
            "M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 " +
                "2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z"
        )
    }

    /** 削除（ゴミ箱） */
    val Delete: ImageVector by lazy {
        materialIcon(
            "Delete",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
        )
    }

    /** すべて削除（ゴミ箱＋掃き出し線） */
    val DeleteSweep: ImageVector by lazy {
        materialIcon(
            "DeleteSweep",
            "M15 16h4v2h-4zm0-8h7v2h-7zm0 4h6v2h-6zM3 18c0 1.1.9 2 2 2h6c1.1 0 " +
                "2-.9 2-2V8H3v10zM14 5h-3l-1-1H6L5 5H2v2h12z"
        )
    }
}
