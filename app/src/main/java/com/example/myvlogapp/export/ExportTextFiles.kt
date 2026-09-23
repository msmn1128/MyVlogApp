package com.example.myvlogapp.export

import java.io.File
import com.example.myvlogapp.VlogClip

// =====================================================================================
// drawtextへ渡すテキストファイルの書き出し。
//
// VlogExporter.kt から切り出したもの。drawtextで描くのはいまは撮影時刻だけ（ひとことと
// タイトルの文言は、絵文字を描くために画像にしている。TextImages.kt）。
// 文字は必ず textfile= 経由で渡す。text= に直接埋め込むと、フィルタグラフとdrawtextの二段階で
// 引用符・コロン・バックスラッシュが解釈され、エスケープの正しさが文字列の中身に左右されてしまう
// （ファイルなら中身は解釈されない）。
//
// 生成したファイルは textFiles へ積む。掃除するのは呼び出し元（VlogExporter.export の finally）で、
// 成功・失敗・キャンセルのいずれでもまとめて消す。
// =====================================================================================

/** 撮影時刻（[VlogClip.timeText]）を1行のテキストファイルへ書き出す */
internal fun writeTimeTextFile(
    workDir: File,
    id: Long,
    clipIndex: Int,
    clip: VlogClip,
    textFiles: MutableList<File>
): File = File(workDir, "time_${id}_$clipIndex.txt")
    .apply { writeText(clip.timeText, Charsets.UTF_8) }
    .also { textFiles += it }
