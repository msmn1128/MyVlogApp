package com.example.myvlogapp.export

import java.io.File
import com.example.myvlogapp.TextSpan
import com.example.myvlogapp.VlogClip

// =====================================================================================
// drawtextへ渡す、行ごとのテキストファイルの書き出し。
//
// VlogExporter.kt から切り出したもの。文字は必ず textfile= 経由で渡す。text= に
// 直接埋め込むと、フィルタグラフとdrawtextの二段階で引用符・コロン・バックスラッシュが
// 解釈され、エスケープの正しさが文字列の中身に左右されてしまう（ファイルなら
// 中身は解釈されない）。
//
// どの関数も、生成したファイルを textFiles へ積む。掃除するのは呼び出し元
// （VlogExporter.export の finally）で、成功・失敗・キャンセルのいずれでもまとめて消す。
// =====================================================================================

/** [VlogClip.visibleTextSpans] 1件と、その各行のテキストファイルの組 */
internal data class SpanLines(val span: TextSpan, val lineFiles: List<File?>)

/**
 * 1クリップぶんの区間（[VlogClip.visibleTextSpans]）ごとに、行単位のテキストファイルを書き出す。
 *
 * drawtextのtext_alignはFFmpeg 7.0以降の機能で、このビルド(6.x)には無い。
 * 複数行を中央揃えにするため、1行につき1つのdrawtextとして描く。
 *
 * クリップの途中でひとことを変えている場合は、区間ごとにこの一式を作る。
 * 動画は切らずに drawtext の enable で出し分けるので、
 * 分割してもクリップは1本のまま（つなぎ目が生まれない）。
 */
internal fun writeSpanTextFiles(
    workDir: File,
    id: Long,
    clipIndex: Int,
    clip: VlogClip,
    textFiles: MutableList<File>
): List<SpanLines> = clip.visibleTextSpans().mapIndexed { spanIndex, span ->
    val lineFiles = span.text.split("\n").mapIndexed { lineIndex, line ->
        // 空行にdrawtextを掛けるとエラーになるので、位置だけ確保して描かない
        if (line.isBlank()) null
        else writeTextFile(
            workDir, "text_${id}_${clipIndex}_${spanIndex}_$lineIndex.txt", line, textFiles
        )
    }
    SpanLines(span, lineFiles)
}

/**
 * タイトルカードの文言（既定は撮影日、自由入力ならその文言）を改行ごとに
 * 行単位のテキストファイルへ書き出す。空行は詰めて無視する
 * （タイトルは[SpanLines]と違って行位置をenableで出し分ける必要が無く、
 * 空行のぶんだけ間隔を空けておく理由が無いため）。
 */
internal fun writeTitleTextFiles(
    workDir: File,
    id: Long,
    titleText: String,
    textFiles: MutableList<File>
): List<File> = titleText.split("\n")
    .filter { it.isNotBlank() }
    .mapIndexed { lineIndex, line ->
        writeTextFile(workDir, "title_${id}_$lineIndex.txt", line, textFiles)
    }

/** 撮影時刻（[VlogClip.timeText]）を1行のテキストファイルへ書き出す */
internal fun writeTimeTextFile(
    workDir: File,
    id: Long,
    clipIndex: Int,
    clip: VlogClip,
    textFiles: MutableList<File>
): File = writeTextFile(workDir, "time_${id}_$clipIndex.txt", clip.timeText, textFiles)

/** drawtextのtextfile=に読ませる1行ぶんのファイルを書く */
private fun writeTextFile(
    workDir: File,
    name: String,
    text: String,
    textFiles: MutableList<File>
): File = File(workDir, name)
    .apply { writeText(text, Charsets.UTF_8) }
    .also { textFiles += it }
