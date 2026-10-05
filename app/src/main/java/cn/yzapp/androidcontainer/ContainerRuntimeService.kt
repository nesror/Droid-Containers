package cn.yzapp.androidcontainer

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
import cn.yzapp.androidcontainer.core.common.ContainerRuntimeActions
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.ContainerRuntime
import cn.yzapp.androidcontainer.core.data.DataGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 容器运行时保活前台服务（方案 §5 后台运行）：
 *
 * - 只要存在 RUNNING 容器（含 autoStart 拉起与手动/compose 启动），就以 specialUse 前台服务
 *   托管进程生命周期，避免 App 退后台后被系统（尤其国内定制 ROM）查杀导致容器与 Docker 服务中断。
 * - 选用 specialUse 而非 dataSync：targetSdk 35+ 对 dataSync 有 ~6 小时硬上限，容器需长期驻留。
 * - 所有容器停止后自动退出；START_STICKY + 空意图自恢复兜底（原生 ROM 杀进程后可重启服务）。
 * - 通知栏提供"停止全部"操作：级联停止容器后服务自退。
 */
class ContainerRuntimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    // 不能在构造期创建：Service 先 newInstance 再 attachBaseContext，
    // 构造期 ContextWrapper 的 baseContext 为 null，applicationContext 会 NPE 闪退
    private val keepAlive by lazy { ServiceKeepAlive(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 息屏后 CPU 休眠会冻结容器进程（proot 随宿主进程一起被 suspend），必须持唤醒锁
        keepAlive.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ContainerRuntimeActions.ACTION_STOP -> {
                AppLogger.i(TAG, "runtime keepalive stop requested")
                stopWatch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ContainerRuntimeActions.ACTION_STOP_ALL -> {
                scope.launch {
                    stopAllRunningContainers()
                    stopWatch()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            // START 或系统重启送达的 null 意图：重新评估状态（无运行容器则自退）
            else -> startInForeground()
        }
        startWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatch()
        keepAlive.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startWatch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            DataGraph.containerRepository.runtimeStates.collect { states ->
                val running = states.values.count { it == ContainerRuntime.RUNNING }
                if (running == 0) {
                    AppLogger.i(TAG, "no running containers, keepalive exits")
                    stopWatch()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification(running)
                }
            }
        }
    }

    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    private suspend fun stopAllRunningContainers() {
        val repository = DataGraph.containerRepository
        val runningIds = repository.runtimeStates.value
            .filterValues { it == ContainerRuntime.RUNNING }
            .keys
        for (id in runningIds) {
            try {
                repository.stop(id)
            } catch (e: Exception) {
                AppLogger.w(TAG, "stop-all: failed to stop container id=$id", e)
            }
        }
        AppLogger.i(TAG, "stop-all finished, count=${runningIds.size}")
    }

    // ---- notifications ----

    private fun startInForeground() {
        val running = DataGraph.containerRepository.runtimeStates.value
            .values.count { it == ContainerRuntime.RUNNING }
        val notification = buildNotification(running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse 类型常量仅 API 34+ 存在；29-33 走两参重载（类型按 Manifest 声明处理）
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(runningCount: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(runningCount))
    }

    private fun buildNotification(runningCount: Int): Notification {
        val stopAllIntent = PendingIntent.getService(
            this,
            0,
            Intent(ContainerRuntimeActions.ACTION_STOP_ALL).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (runningCount > 0) {
            getString(R.string.notif_runtime_text, runningCount)
        } else {
            getString(R.string.notif_runtime_starting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_runtime_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_runtime_stop_all), stopAllIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_runtime_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RuntimeKeepalive"
        private const val CHANNEL_ID = "container_runtime"
        private const val NOTIFICATION_ID = 1003
    }
}
