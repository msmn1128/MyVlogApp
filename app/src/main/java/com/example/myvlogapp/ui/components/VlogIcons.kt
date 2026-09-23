package com.example.myvlogapp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * タイムライン操作バーのアイコン。
 *
 * material-icons-extended を足せば undo / redo / delete_sweep は既製品で揃うが、
 * あれは3万個ぶんのImageVectorを含む巨大な依存で、ビルドが重くなる
 * （R8で未使用ぶんは削れるが、使うのは数個だけ）。必要なぶんだけをここに置く。
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

    /** 選択中（チェックマーク） */
    val Check: ImageVector by lazy {
        materialIcon("Check", "M9 16.17L4.83 12l-1.41 1.42L9 19 21 7l-1.41-1.41z")
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

    /** タイムラインのミュート：オフ（音が出ている状態） */
    val VolumeUp: ImageVector by lazy {
        materialIcon(
            "VolumeUp",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 " +
                "2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 " +
                "6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
        )
    }

    /** タイムラインのミュート：オン（音を消している状態） */
    val VolumeOff: ImageVector by lazy {
        materialIcon(
            "VolumeOff",
            "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.42.05-.63zm" +
                "2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-" +
                "2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 " +
                "5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 " +
                "21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"
        )
    }

    /** 動画が見つからない（移動・削除された、権限が取り消された）クリップの目印 */
    val Warning: ImageVector by lazy {
        materialIcon("Warning", "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z")
    }
}
