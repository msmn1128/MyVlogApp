package com.example.myvlogapp.export

// =====================================================================================
// 本数が多いときの、区切りごとの書き出し。
//
// 全クリップを1回のFFmpeg呼び出しへ同時に入力すると、使うメモリが本数に比例して増え続ける。
// 実測（4GBのエミュレータ、4K・3秒のクリップ）で、書き出しの開始時に1本あたり約30MB
// （全入力を同時に開くぶん）、処理が進むと1本処理するごとに約19MB（処理し終えた入力の
// ぶんが最後まで解放されない）増え、25本で約1.6GB、100本では途中で強制終了された。
//
// そこで[SEGMENT_MAX_CLIPS]本を超えるときは、その本数ずつの区切りに分けて1区切りずつ
// 書き出し（.movに、映像はH.264、音声は無圧縮のPCM）、最後に映像はそのままつなぎ（再圧縮しない）、
// 音声だけAACに1回変換して仕上げる。同時に開く動画は最大[SEGMENT_MAX_CLIPS]本になり、
// メモリは本数に関係なく一定で済む（4Kで約0.8GB）。
//
// 音声を区切りごとにAACにしないのは、AACは区切りの頭にエンコーダの遅延ぶんの無音が入り、
// つなぐとそのぶん音が映像からずれていくため。PCMなら長さが正確で、変換は仕上げの1回だけで済む。
// =====================================================================================

/**
 * 区切りごとに分けて書き出す本数の境目と、1区切りの本数。
 * これ以下なら従来どおり1回で書き出す（よく使う経路は変えない）。
 */
internal const val SEGMENT_MAX_CLIPS = 10

/** クリップの添字を、[maxPerSegment]本ずつの区切りに分ける */
internal fun planSegments(clipCount: Int, maxPerSegment: Int = SEGMENT_MAX_CLIPS): List<IntRange> =
    (0 until clipCount).chunked(maxPerSegment).map { it.first()..it.last() }

/**
 * 区切りのファイルを順につなぐための、FFmpegのconcat形式の一覧。
 * パスは単引用符で囲み、中の単引用符は「'\''」に書き換える（concat形式の決まり）。
 */
internal fun concatListText(paths: List<String>): String = buildString {
    append("ffconcat version 1.0\n")
    paths.forEach { path -> append("file '").append(path.replace("'", "'\\''")).append("'\n") }
}
