package com.example.myvlogapp.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// =====================================================================================
// タイムライン欄の高さを、中身（見出し・操作バー・タイル・波形）が収まるまで広げるためのもの。
//
// 画面の縦の配分は比率（weight）の固定値で、既定の文字サイズで波形がちょうど収まるように
// 決めてある。端末の文字サイズを大きくすると見出しやタイルの文字だけが背を伸ばし、
// 波形が欄の下へ押し出されて、欄をスクロールしないと出てこなかった（文字1.3倍で確認）。
//
// 中身の本来の高さと、比率1あたりの高さを実際のレイアウトから測り、足りないぶんだけ
// タイムラインの比率を上乗せする（その分はプレビューなどから差し引く）。
// 比率1あたりの高さは、欄の実際の高さ÷そのとき渡した比率で出す。ボタンや隙間などの
// 固定の高さは比率に関係なく決まるので、比率を変えてもこの値は変わらず、1回で収まる。
// =====================================================================================

@Stable
internal class TimelineFit {
    /** 比率1あたりの高さ(px)。まだ測っていなければ0 */
    private var pxPerWeight by mutableFloatStateOf(0f)

    /** タイムライン欄の中身が収まる高さ(px)。欄の内側の余白を含む */
    private var neededPx by mutableIntStateOf(0)

    /**
     * レイアウトの結果を受け取る。
     * キーボードの開閉中は画面の高さそのものが毎コマ変わり、1コマ遅れの値で計算すると
     * 配分が揺れるので、キーボードが閉じているときの値だけを使う（開いている間は上乗せしない）。
     */
    fun onMeasured(cardPx: Int, contentPx: Int, appliedWeight: Float, isImeVisible: Boolean) {
        neededPx = contentPx
        if (!isImeVisible && appliedWeight > 0f && cardPx > 0) pxPerWeight = cardPx / appliedWeight
    }

    /** [baseWeight]に上乗せする比率。[maxExtra]まで */
    fun extraWeight(baseWeight: Float, maxExtra: Float): Float =
        timelineExtraWeight(neededPx, pxPerWeight, baseWeight, maxExtra)
}

/**
 * 中身が収まるのに要る比率から、いまの比率を引いた不足分（0〜[maxExtra]）。
 * 測る前（[pxPerWeight]が0）は上乗せしない。
 */
internal fun timelineExtraWeight(
    neededPx: Int,
    pxPerWeight: Float,
    baseWeight: Float,
    maxExtra: Float
): Float {
    if (pxPerWeight <= 0f || maxExtra <= 0f) return 0f
    return (neededPx / pxPerWeight - baseWeight).coerceIn(0f, maxExtra)
}
