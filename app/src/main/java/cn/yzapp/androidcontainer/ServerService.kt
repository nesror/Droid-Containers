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
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.common.LanAddress
import cn.yzapp.androidcontainer.core.common.RemoteControlActions
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.server.EntitlementGate
import cn.yzapp.androidcontainer.core.server.RemoteHttpServer
import cn.yzapp.androidcontainer.core.server.ServiceAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 远程控制前台服务（M8，m8_m9 §3.1-2）：specialUse 类型常驻（Android 15+ 的 dataSync
 * 有 ~6h 上限且超时静默停服，与"远程控制常驻"语义冲突，审查 P1-3），
 * 托管 [RemoteHttpServer] 的 Web 实例与 Docker 兼容实例。
 * 开关/端口读取自设置；修改端口后需关闭再开启开关（阶段一不做热重载）。
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: RemoteHttpServer? = null
    private var entitlementObserved = false

    /**
     * 当前 Bearer token（审查 P1-4）：交给 [RemoteHttpServer] 的鉴权中间件按请求读取，
     * 设置页「重置 token」后旧 token 立即失效，无需重启服务。
     */
    private val apiTokenState = MutableStateFlow<String?>(null)
    private var tokenObserved = false
    // 不能在构造期创建：Service 先 newInstance 再 attachBaseContext，
    // 构造期 ContextWrapper 的 baseContext 为 null，applicationContext 会 NPE 闪退
    private val keepAlive by lazy { ServiceKeepAlive(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 息屏后 CPU 休眠会导致入站连接无响应（Doze 期间前台服务也不例外），必须持唤醒锁
        keepAlive.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i("Server", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            RemoteControlActions.ACTION_STOP -> {
                AppLogger.i("Server", "remote server stop requested")
                server?.stopAll()
                server = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            RemoteControlActions.ACTION_START -> startInForeground()
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        scope.launch {
            try {
                startServer()
            } catch (e: Exception) {
                // 绑定失败等：落日志（便于无通知权限时排查）并通知展示错误后退出
                AppLogger.e("Server", "remote server start failed", e)
                updateNotification(getString(R.string.notif_remote_failed, e.message ?: ""))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepAlive.release()
        server?.stopAll()
        server = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startServer() {
        val settings = DataGraph.settingsRepository
        val webEnabled = settings.webEnabled.first()
        val dockerEnabled = settings.dockerEnabled.first()
        // 远程控制属于模板包（m8_m9 §7）：未解锁不启动 Web 实例（Docker 兼容实例不受门禁影响）
        val state = DataGraph.entitlementRepository.state.first()
        val webAllowed = EntitlementGate.actionFor(state, running = false) == ServiceAction.START
        AppLogger.i(
            "Server",
            "starting web=$webEnabled webAllowed=$webAllowed docker=$dockerEnabled " +
                "entitlement=${EntitlementGate.stateName(state)}",
        )
        if ((webEnabled && !webAllowed) && !dockerEnabled) {
            // 只有 Web 且未解锁：完全不开端口（不留下一个只会报 402 的口）
            stopSelf()
            return
        }
        // App 自启恢复与设置页开关都会发 ACTION_START：先停掉旧实例再重建，
        // 否则同一端口二次绑定 → BindException（旧实例的引擎还占着端口）
        server?.stopAll()
        server = null
        val newServer = RemoteHttpServer(
            images = DataGraph.imageRepository,
            containers = DataGraph.containerRepository,
            compose = DataGraph.composeRepository,
            entitlement = DataGraph.entitlementRepository,
            settings = settings,
        )
        if (webEnabled && webAllowed) {
            // token 走 StateFlow（审查 P1-4）：设置页「重置」后鉴权中间件立即读到新值，
            // 旧 token 同时失效，无需重启服务
            apiTokenState.value = settings.ensureApiToken()
            newServer.startWeb(settings.webPort.first(), apiTokenState)
        }
        if (dockerEnabled) {
            // 局域网暴露需用户在设置页显式开启（默认回环，审查 P0-1）
            newServer.startDocker(settings.dockerPort.first(), settings.dockerLanEnabled.first())
        }
        server = newServer
        observeEntitlementOnce()
        observeTokenChanges()
        AppLogger.i("Server", "remote server started web=${newServer.isWebRunning} docker=${newServer.isDockerRunning}")
        updateNotification(buildAddressText())
    }

    /**
     * 监听权益变化：明确 `Locked`（退款 / 换账号）时停掉 Web 实例；
     * `Unknown` 不动作（见 [EntitlementGate.actionFor]，避免 Play 未就绪时抖动）。
     * 服务可能多次 restart，只挂一个监听器。
     */
    private fun observeEntitlementOnce() {
        if (entitlementObserved) return
        entitlementObserved = true
        scope.launch {
            DataGraph.entitlementRepository.state.collect { state ->
                if (state !is EntitlementState.Locked) return@collect
                if (server?.isWebRunning != true) return@collect
                AppLogger.i("Server", "entitlement locked: stopping web console")
                server?.stopWeb()
                updateNotification(buildAddressText())
                if (server?.isWebRunning != true && server?.isDockerRunning != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * 持续同步 token（审查 P1-4）：设置页「重置 token」写 DataStore 后，
     * 服务端鉴权中间件必须立刻改用新值，否则旧 token 仍可通过。
     */
    private fun observeTokenChanges() {
        if (tokenObserved) return
        tokenObserved = true
        scope.launch {
            DataGraph.settingsRepository.apiToken.collect { apiTokenState.value = it }
        }
    }

    private suspend fun buildAddressText(): String {
        val settings = DataGraph.settingsRepository
        val ip = LanAddress.firstIpv4() ?: "localhost"
        val parts = mutableListOf<String>()
        if (server?.isWebRunning == true) {
            parts.add("http://$ip:${settings.webPort.first()}")
        }
        if (server?.isDockerRunning == true) {
            parts.add("docker -H tcp://$ip:${settings.dockerPort.first()}")
        }
        return if (parts.isEmpty()) getString(R.string.notif_remote_starting) else parts.joinToString("  ")
    }

    // ---- notifications ----

    private fun startInForeground() {
        val notification = buildNotification(getString(R.string.notif_remote_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse：dataSync 在 Android 15+ 有 ~6h 上限，超时系统静默停服（审查 P1-3）；
            // specialUse 常量仅 API 34+ 存在，29-33 走两参重载（类型按 Manifest 声明处理）
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_remote_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_remote_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "remote_control"
        private const val NOTIFICATION_ID = 1002

        /** feature 层触发入口（隐式 action + 包名限定，不依赖 app 类）。 */
        fun start(context: Context) {
            val intent = Intent(RemoteControlActions.ACTION_START).setPackage(context.packageName)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(RemoteControlActions.ACTION_STOP).setPackage(context.packageName)
            context.startService(intent)
        }
    }
}
