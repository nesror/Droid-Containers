package cn.yzapp.androidcontainer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.model.PullStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * dataSync 前台服务（方案 §3.5/§5）：拉取期间保活 + 进度通知。
 * feature 层通过 action 触发（隐式 intent，避免跨模块类依赖）。
 */
class DataSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ref = intent?.getStringExtra(EXTRA_REF)
        if (ref.isNullOrBlank()) {
            // stopSelf(startId) 而非 stopSelf()（审查 P1-20）：只结束本次启动请求，
            // 避免把并发到来的新一次拉取服务一并杀掉
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startInForeground(ref)

        progressJob?.cancel()
        progressJob = scope.launch {
            val repository = DataGraph.imageRepository
            // 通知随进度刷新
            val notifier = launch {
                repository.pullStates.collectLatest { states ->
                    states[ref]?.let { updateNotification(ref, it) }
                }
            }
            try {
                repository.pull(ref)
            } catch (_: Exception) {
                // 失败进度已写入 repository.pullStates（FAILED），通知同步展示
            } finally {
                notifier.cancel()
            }
            // stopSelf(startId)（审查 P1-20）：若期间又来了新的拉取请求（startId 更大），
            // 本次结束不再连带停掉服务
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun startInForeground(ref: String) {
        val notification = buildNotification(ref, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(ref: String, progress: cn.yzapp.androidcontainer.core.model.PullProgress) {
        val notification = buildNotification(ref, progress)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(ref: String, progress: cn.yzapp.androidcontainer.core.model.PullProgress?): Notification {
        val title = when (progress?.stage) {
            null, PullStage.IDLE -> getString(R.string.notif_pull_started)
            PullStage.FAILED -> getString(R.string.notif_pull_failed)
            PullStage.READY -> getString(R.string.notif_pull_done)
            else -> getString(R.string.notif_pull_running)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(ref)
            .setOngoing(progress == null || (progress.stage != PullStage.READY && progress.stage != PullStage.FAILED))
            .setOnlyAlertOnce(true)

        if (progress != null) {
            // 优先跨层累计字节（进度条不随层切换回跳）；旧引擎无累计值时退回当前层
            val (done, total) = if (progress.overallTotalBytes > 0) {
                progress.overallDownloadedBytes to progress.overallTotalBytes
            } else {
                progress.downloadedBytes to progress.totalBytes
            }
            if (total > 0) {
                builder.setProgress(100, ((done * 100) / total).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PULL = "cn.yzapp.androidcontainer.action.PULL"
        const val EXTRA_REF = "extra_ref"
        private const val CHANNEL_ID = "data_sync"
        private const val NOTIFICATION_ID = 1001

        /** feature 层触发入口（隐式 action + 包名限定，不依赖 app 类）。 */
        fun startPull(context: Context, ref: String) {
            val intent = Intent(ACTION_PULL).setPackage(context.packageName).putExtra(EXTRA_REF, ref)
            context.startForegroundService(intent)
        }
    }
}
