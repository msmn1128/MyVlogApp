package com.example.myvlogapp.export

import android.media.MediaFormat

// =====================================================================================
// HDR（10ビット）で撮った動画を、書き出しでSDRへ変換する。
//
// 最近のスマホは既定でHDR（HLGやPQ＝HDR10）で撮る機種がある。書き出しは色を変換せずに
// そのままH.264（8ビット・SDR）へ入れていたため、HDRの信号がSDRとして解釈され、
// 白っぽく色が抜けて見えていた（エミュレータで合成したHLG/PQのカラーバーで、SDRとの平均のずれが
// HLG 58・PQ 84（0〜255）。PQは白が148の暗い灰色になった）。変換後は HLG 4.1・PQ 6.9、白は249。
//
// HDRのクリップだけ、フィルタの先頭で「HDRの信号 → 直線の明るさ → BT.709の色域 →
// 明るさの圧縮（トーンマップ） → SDRの信号」に変換する（zscaleとtonemap。同梱のFFmpegは
// libzimgを含むビルド）。SDRのクリップは今までどおり何もしない。
// =====================================================================================

/** HDRの伝達特性（信号と明るさの対応）。値はzscaleに渡す名前 */
internal enum class HdrTransfer(val zscaleName: String) {
    /** HDR10など。絶対的な明るさ（nit）で符号化されている */
    PQ("smpte2084"),

    /** 放送やスマホのHDR（Pixel・iPhoneのHDR動画など） */
    HLG("arib-std-b67")
}

/**
 * 映像トラックの情報から、HDRか（どの伝達特性か）を読む。SDRや情報が無いときは null。
 * 動画を開くのは呼び出し側（ClipProbe.kt。音声の有無と一緒に1回で読む）。
 */
internal fun hdrTransferOf(videoFormat: MediaFormat): HdrTransfer? {
    if (!videoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) return null
    return when (videoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> HdrTransfer.PQ
        MediaFormat.COLOR_TRANSFER_HLG -> HdrTransfer.HLG
        else -> null
    }
}

/**
 * HDRの映像をSDR（BT.709）へ変換するフィルタ。クリップのフィルタの先頭（拡大・縮小より前）に付ける。
 *
 * - `npl=203`: HDRの基準の白（BT.2408でSDRの白に当たる203nit）を、変換後の白（1.0）に合わせる。
 *   既定の100にすると、ふつうの白が2倍の明るさと見なされて、圧縮で全体が暗くなる。
 * - `tonemap=mobius:param=0.9`: 明るさの90%まではそのまま保ち、それより明るいハイライトだけを
 *   なめらかに圧縮する。既定の0.3だと30%から圧縮が始まり、ふつうの白まで215に下がって暗く見えた
 *   （0.9で249）。hableは全体を圧縮するので、ふつうの場面まで暗くなる。
 * - `desat=0`: 明るい部分の色を薄めない（既定では白っぽく色が抜ける）。
 * - 入力の色域（BT.2020）・行列・範囲も明示する。素材の情報が欠けていてもHDRとして正しく読むため。
 */
internal fun hdrToSdrFilter(transfer: HdrTransfer): String =
    "zscale=tin=${transfer.zscaleName}:pin=bt2020:min=bt2020nc:rin=tv:t=linear:npl=203," +
        "format=gbrpf32le," +
        "zscale=p=bt709," +
        "tonemap=tonemap=mobius:param=0.9:desat=0," +
        "zscale=t=bt709:m=bt709:r=tv," +
        "format=yuv420p"
