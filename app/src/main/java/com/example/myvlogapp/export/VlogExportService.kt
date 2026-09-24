package com.example.myvlogapp.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.example.myvlogapp.MainActivity
import com.example.myvlogapp.VlogClip

/**
 * VLOG書き出しをフォアグラウンドサービスとして実行する。
 *
 * ViewModelのviewModelScopeで直接実行すると、フォアグラウンドサービスでも
 * WorkManagerでもないため、アプリをバックグラウンドに回したときにOSが
 * プロセスごと回収することがある。その場合try-finallyが実行されないまま
 * 打ち切られ、MediaStoreにIS_PENDINGの壊れた動画が残ってしまう
 * （[VlogExporter.cleanupOrphanedPendingFiles]で次回起動時に掃除はするが、
 * そもそも回収されにくくする方が先）。
 * フォアグラウンドサービスにしてOSに「ユーザーが注視している処理」だと
 * 伝えることで、Activity/ViewModelが破棄されても処理を続けられるようにする。
 *
 * VlogClipはUriを含みプロセス内でしか意味を持たないため、Intentへ
 * シリアライズせず [pendingExport] へ直接渡してから起動する（この設計は
 * サービスと呼び出し元が常に同一プロセスであることが前提）。
 */
class VlogExportService : Service() {

    companion object {
        private const val CHANNEL_ID = "vlog_export"
        private const val NOTIFICATION_ID = 1
        // 完了/中止/失敗の結果通知は進行中の通知（NOTIFICATION_ID）とは別IDにする。
        // stopSelf()で進行中の通知は消えるが、結果通知は別IDなので残り続け、
        // アプリを完全に閉じていても（ExportStatus.emitのToast購読者がいなくても）
        // ユーザーに結果が伝わる（iOS版のローカル通知相当）。
        private const val RESULT_NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "com.example.myvlogapp.action.CANCEL_EXPORT"

        /** 通知の進捗バーの目盛り数。0〜100で百分率そのものとして扱う */
        private const val NOTIFICATION_PROGRESS_MAX = 100

        /**
         * [start]から[onStartCommand]まで、書き出し内容をIntentを経由せず直接受け渡すための
         * 保留状態。1つのdata classにまとめてあるのは、書き出しオプションが増えるたびに
         * ここ・[start]の引数・[onStartCommand]の読み出し・[VlogExporter.export]呼び出しの
         * 4箇所を機械的に増やす作業を、フィールド追加1箇所で済ませるため。
         */
        private data class PendingExport(
            val clips: List<VlogClip>,
            val includeTitle: Boolean,
            val muted: Boolean,
            val customTitleText: String?
        )

        private var pendingExport: PendingExport? = null

        fun start(
            context: Context,
            clips: List<VlogClip>,
            includeTitle: Boolean = true,
            muted: Boolean = false,
            customTitleText: String? = null
        ) {
            pendingExport = PendingExport(clips, includeTitle, muted, customTitleText)
            ContextCompat.startForegroundService(
                context,
                Intent(context, VlogExportService::class.java)
            )
        }

        /**
         * 実行中でなければ何もしない。
         *
         * 書き出しが終わった直後（サービスは既に停止済み）に「中止」を押すと、
         * バックグラウンド状態のアプリからの新規startServiceになり、
         * Android 8以降のバックグラウンド起動制限下ではIllegalStateExceptionが
         * 投げられうる。runCatchingで包むのは、その場合も「中止できなかった」だけで
         * アプリ全体を落とさないようにするため。
         */
        fun cancel(context: Context) {
            if (!ExportStatus.isRunning) return
            runCatching {
                context.startService(
                    Intent(context, VlogExportService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }
    }

    // Activityに依存させないため、ViewModelScopeではなく自前のスコープを持つ
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exportJob: Job? = null

    /**
     * 最後に受け取った起動要求のID。書き出しを終えて畳むときに[stopSelf]へ渡す。
     *
     * 引数なしのstopSelf()は、その直後に届いた新しい書き出しの起動要求があっても
     * サービスごと止めてしまい、始まったばかりの書き出しをonDestroyが中止してしまう。
     * IDを渡せば、それより新しい要求が届いていたときは止まらない。
     * 書き出し本体の「この書き出しのID」ではなく「最後に受け取ったID」なのは、
     * 中止の要求（ACTION_CANCEL）もIDを進めるため。書き出しのIDで止めようとすると、
     * 中止したあとに止まらず、進行中の通知が残り続ける。
     */
    private var latestStartId = 0

    /**
     * 書き出しを受け付けている最中か。[stateLock]の中でだけ読み書きする。
     *
     * 以前は「書き出しのジョブが生きているか」で多重起動を断っていた。ジョブは状態を「待機中」へ
     * 戻したあとも、畳み終えるまで少しだけ生きている。その隙に画面から次の書き出しを押すと、
     * 画面は待機中を見て送り出すのに、サービスは実行中と見て黙って捨てていた。
     * 状態を戻すのと同じロックの中で下ろせば、画面が待機中を見たときには必ず受け付けられる。
     */
    private var exporting = false

    /** [exporting]・[latestStartId]と、書き出しの状態（[ExportStatus]）の切り替えをまとめて守る */
    private val stateLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            synchronized(stateLock) { latestStartId = startId }
            VlogExporter.cancel()
            // 実行中なら、中止されたジョブのfinallyがstopSelfまで面倒を見る。
            // 実行中でない場合（書き出しが終わった直後に「中止」を押した等）は、
            // このIntentのせいで起動しただけのサービスが何もせず残り続けてしまうため、
            // ここで自分で畳む。
            val job = exportJob
            if (job?.isActive == true) job.cancel() else stopSelf(startId)
            return START_NOT_STICKY
        }

        // ここから下は[start]（startForegroundService）で起動されたとき。この起動では、何もせずに
        // 畳む場合でも先にstartForegroundを呼ばなければならない。呼ばずに戻ったり止めたりすると、
        // OSが「startForegroundを呼ばなかった」としてアプリごと強制終了させる。
        val pending = pendingExport
        pendingExport = null

        val accepted: PendingExport = synchronized(stateLock) {
            latestStartId = startId

            // 既に実行中なら多重起動しない（連打・二重タップ対策）。後から来た書き出しは捨てる。
            // 通知はいまの書き出しの進み具合のまま出し直す（「準備中...」へ戻して見せない）
            if (exporting) {
                val running = ExportStatus.state.value as? ExportState.Running
                startForegroundWithNotification(running?.message ?: "準備中...", running?.progress)
                return START_NOT_STICKY
            }

            if (pending == null || pending.clips.isEmpty()) {
                startForegroundWithNotification("準備中...")
                stopSelf(startId)
                return START_NOT_STICKY
            }

            exporting = true
            startForegroundWithNotification("準備中...")
            ExportStatus.setRunning("準備中...")
            pending
        }
        val (clips, includeTitle, muted, customTitleText) = accepted

        // 書き出しを終えたあとに届いた進捗を捨てるための印と、そのロック。
        //
        // 中止するとコルーチンはすぐ抜けて下のfinallyで「待機中」に戻すが、FFmpegのネイティブ側は
        // 中止に気付くまで少しエンコードを続け、その間も進捗のコールバックを送ってくる。
        // それをそのまま反映すると、サービスはもう止まっているのに状態が「書き出し中」へ
        // 書き戻され、進行中の通知も出し直される。画面は「中止」ボタンのまま戻らず、
        // アプリを再起動するまで書き出せなくなっていた。
        // 印の確認と反映をまとめてロックで囲むのは、確認した直後にfinallyが割り込んで
        // 「待機中」へ戻したあとに、反映だけが遅れて走るのを防ぐため。
        val progressLock = Any()
        var finished = false

        exportJob = serviceScope.launch {
            try {
                val name = VlogExporter.export(
                    context = applicationContext,
                    clips = clips,
                    includeTitle = includeTitle,
                    muted = muted,
                    customTitleText = customTitleText,
                    // FFmpegの統計コールバックのスレッドから直接呼ばれうる。
                    // StateFlowへの代入もNotificationManager.notifyもスレッド安全なので、
                    // そのまま呼んでよい（以前はrunBlockingで呼び出し元へ戻していた）。
                    onProgress = { message, progress ->
                        synchronized(progressLock) {
                            if (!finished) {
                                ExportStatus.setRunning(message, progress)
                                updateNotification(message, progress)
                            }
                        }
                    }
                )
                val message = "ギャラリーに保存しました\n$name"
                ExportStatus.emit(VlogEvent.Message(message))
                notifyResult("書き出し完了", message)
            } catch (e: CancellationException) {
                ExportStatus.emit(VlogEvent.Message("書き出しを中止しました"))
                notifyResult("書き出しを中止しました", "書き出しを中止しました")
            } catch (e: Exception) {
                val message = e.message ?: "書き出しに失敗しました"
                ExportStatus.emit(VlogEvent.Message(message))
                notifyResult("書き出しに失敗しました", message)
            } finally {
                // 先に遅れて届く進捗を止めてから、待機中へ戻す（逆だと、戻した直後の進捗が
                // 「書き出し中」へ書き戻してしまう）
                synchronized(progressLock) { finished = true }
                // 受け付けを下ろすのと待機中へ戻すのは同じロックの中で行う（理由は[exporting]）。
                // 止めるIDもここで読む。このあとに次の書き出しが届いていれば、そちらのIDの方が
                // 新しいのでstopSelfはサービスを止めない（次の書き出しを畳んでしまわない）
                val stopId = synchronized(stateLock) {
                    exporting = false
                    ExportStatus.setIdle()
                    latestStartId
                }
                // stopForeground(true)相当。onDestroyに任せず自分で止める
                // （サービスが仕事を終えたのに通知が残り続けるのを防ぐ）
                stopSelf(stopId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15以降、mediaProcessing型（と、Android 14以前で使うdataSync型）の
     * フォアグラウンドサービスには24時間あたり合計6時間の上限があり、超えるとOSがこれを呼ぶ。数秒以内にstopSelf()しないとOSがアプリを
     * 異常終了させるため、書き出しを中止して即座に畳む（通常の書き出し時間では到達しない）。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        VlogExporter.cancel()
        exportJob?.cancel()
        stopSelf()
    }

    /**
     * サービスが畳まれるときは、コルーチンだけでなくFFmpegのセッション自体も止める。
     *
     * exportJob.cancel()だけだと、待っているコルーチンが抜けるのは早いが、ネイティブ側の
     * エンコードはそのまま最後まで走り続ける（CPUと電池を使い続け、作業ファイルの掃除も
     * 走らない）。OSがサービスを停止した場合など、ACTION_CANCEL や onTimeout を
     * 経由しない経路がここなので、ここでも明示的に止める。
     */
    override fun onDestroy() {
        VlogExporter.cancel()
        exportJob?.cancel()
        serviceScope.cancel()
        ExportStatus.setIdle()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "VLOG書き出し", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    /**
     * @param progress 0f〜1fの進捗。分かっている間だけ実際の割合のバーを出し、
     *   分からない工程（準備中・保存中）は不定形のバーにする。
     */
    private fun buildNotification(message: String, progress: Float?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VLOGを書き出し中")
            .setContentText(message)
            // アプリ独自のアイコンはアダプティブアイコン形式で通知アイコンに使えないため、
            // システム標準の進行系アイコンを使う
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            // アプリを開かなくても通知から止められるようにする（中止の経路はACTION_CANCELと同じ）
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "中止", cancelIntent())
            .apply {
                if (progress == null) setProgress(0, 0, true)
                else setProgress(
                    NOTIFICATION_PROGRESS_MAX,
                    (progress * NOTIFICATION_PROGRESS_MAX).toInt()
                        .coerceIn(0, NOTIFICATION_PROGRESS_MAX),
                    false
                )
            }
            .build()

    /**
     * 通知をタップしたときにアプリを開く。以前は開く先が無く、タップしても何も起きなかった。
     * ランチャーから開くのと同じ形（MAIN/LAUNCHER）にしておくと、アプリがすでに開いていれば
     * 作り直さずにそのまま前へ出る。
     */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 書き出し中の通知の「中止」。画面の「中止」ボタンと同じく、このサービスへACTION_CANCELを送る */
    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, VlogExportService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun startForegroundWithNotification(message: String, progress: Float? = null) {
        startForeground(NOTIFICATION_ID, buildNotification(message, progress), foregroundServiceType())
    }

    /**
     * 動画の変換には Android 15 で専用の mediaProcessing 型が用意された。それより前の端末には
     * 無いので dataSync 型で代える（AndroidManifest.xml の宣言と権限は両方持っている）。
     * dataSync はデータの転送・同期向けで、Play の申告で用途が合わないと判断されうるため、
     * 使える端末では必ず mediaProcessing を使う。
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun updateNotification(message: String, progress: Float?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message, progress))
    }

    /**
     * 完了・中止・失敗の結果を、進行中の通知とは別の通知として出す。
     * setOngoing(true)の進行中通知はstopSelf()で消えてしまうため、それとは
     * 独立に結果だけを伝える（Activityが破棄されていても届く）。
     */
    private fun notifyResult(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }
}
