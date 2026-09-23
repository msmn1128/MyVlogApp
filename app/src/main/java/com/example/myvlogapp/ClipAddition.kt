package com.example.myvlogapp

/**
 * 動画を追加するとき、選ばれた動画をどう振り分けるか（[planAddition]の結果）。
 *
 * @param toLoad メタデータを読んで追加を試みるもの（選んだ順）
 * @param alreadyAdded すでにタイムラインにあるので外したもの
 * @param overLimit 上限を超えるので、読まずに断ったもの
 */
internal data class AdditionPlan<T>(val toLoad: List<T>, val alreadyAdded: Int, val overLimit: Int)

/**
 * 選ばれた動画を、追加済み・上限超え・読み込むものに振り分ける。[VlogViewModel.addClips]から
 * 切り出したもの（ViewModelの中では単体テストで守れなかったため）。
 *
 * - 同じものが2回選ばれていても1回として扱う（呼び出し元が誤って同じ動画を2回渡した場合など）。
 * - 同じ動画かはURIの一致で判断する。ファイル名＋サイズなどの内容で判断すると、偶然一致した
 *   別の動画を黙って外してしまう（取りこぼし）おそれがあるため。アプリ内ギャラリーとファイル選択の
 *   両方から同じ動画を選ぶと重複に気付けないのは、既知の制約として残す。
 * - 件数は、引き算で辻褄を合わせるのではなく理由ごとに数える。選んだ本数から引いていた頃は、
 *   同じURIを2回渡しただけで「1件は追加済み」と出ていた（タイムラインには無いのに）。
 * - 上限（[limit]）に入りきらない分は、メタデータを読む前に断る。読んでから捨てていた頃は、
 *   残り5本の枠へ50本選ぶと、入らない45本ぶんまで読むのを待たされていた。入れる分は選んだ順に
 *   先頭から取る。その中に読めない動画が混ざると入る本数が空きより少なくなるが、それは通知で伝わる。
 *
 * @param requested 選ばれた動画（選んだ順）
 * @param existing いまタイムラインにある動画
 * @param currentCount いまのタイムラインの本数
 */
internal fun <T> planAddition(
    requested: List<T>,
    existing: Set<T>,
    currentCount: Int,
    limit: Int = MAX_CLIPS
): AdditionPlan<T> {
    val distinct = requested.distinct()
    val fresh = distinct.filter { it !in existing }
    val room = (limit - currentCount).coerceAtLeast(0)
    val toLoad = fresh.take(room)
    return AdditionPlan(
        toLoad = toLoad,
        alreadyAdded = distinct.size - fresh.size,
        overLimit = fresh.size - toLoad.size
    )
}
