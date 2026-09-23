package com.example.myvlogapp

/**
 * 前回の続き（自動保存）を、いつ書き換えてよいか。[VlogViewModel]から切り出したもの。
 *
 * ViewModelの中にあった頃は、Androidの部品と絡んでいて単体テストで守れなかった。
 * 判断だけをここへ出し、ViewModelは「保存してよいか」を聞いてから書くだけにしてある。
 *
 * 決まりは2つ:
 * - 復元が済むまでは書かない。済む前に書くと、復元前の空の一覧で前回の内容を上書きしてしまう。
 * - 復元で開けない動画を落とした回は、何か編集するまで書かない。開けなかったのが一時的なこと
 *   （使っていないアプリの権限をAndroidが自動で取り消した、SDカードが外れていた、クラウド上の
 *   ファイルがオフラインだった）はよくある。開けた分だけを書き戻すと、落とした動画の編集内容が
 *   保存からも消え、権限を許可し直しても続きが戻らなくなる。編集は必ず「もとに戻す」の履歴に
 *   積まれる（撮影時刻の取り直しは積まれない）ので、それが押せるようになったことを編集の合図にする。
 */
internal class AutosavePolicy {
    private var restored = false
    private var keepStoredUntilEdited = false

    /** 前回の続きの復元が済んだ。[droppedCount]は開けなくて落とした動画の本数 */
    fun onRestored(droppedCount: Int) {
        restored = true
        keepStoredUntilEdited = droppedCount > 0
    }

    /**
     * 一覧が変わるたびの自動保存で、書いてよいか。
     * 編集の合図（[canUndo]）を一度受け取ったら、以後は保留しない。
     */
    fun shouldSave(canUndo: Boolean): Boolean {
        if (!restored) return false
        if (keepStoredUntilEdited) {
            if (!canUndo) return false
            keepStoredUntilEdited = false
        }
        return true
    }

    /**
     * 画面を閉じるとき（自動保存の待ちが取り消される）に、最後の状態を書いてよいか。
     * [canUndo]も見るのは、編集した直後に閉じると、自動保存側が編集の合図を受け取って
     * 保留を解く前にここへ来ることがあるため。
     */
    fun shouldSaveOnExit(canUndo: Boolean): Boolean =
        restored && (!keepStoredUntilEdited || canUndo)
}
