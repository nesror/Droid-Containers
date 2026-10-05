package cn.yzapp.androidcontainer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * 前台服务配套 CPU/网络保活。
 *
 * 前台服务只提升进程优先级，**不阻止 CPU 深度休眠**：息屏充电转 Doze 后，
 * 无唤醒锁时入站 TCP 无法被及时响应，表现为「息屏后 Docker/Web 服务不可达、
 * 容器进程被冻结」（同一根因，息屏后 CPU 进入 suspend）。
 *
 * - CPU 锁：[PowerManager.PARTIAL_WAKE_LOCK]，持锁期间 CPU 不休眠；
 * - Wi-Fi 锁：API 29+ 用低延迟模式，26-28 用高性能模式，避免 Wi-Fi 息屏省电导致丢包/断连。
 *
 * 前提是应用已加入电池优化白名单（设置页「后台运行」引导）：
 * 未加白时系统可能在 Doze 中强制忽略唤醒锁并切断网络。
 *
 * 生命周期：服务 onCreate 调 [acquire]，onDestroy 调 [release]；引用计数关闭，幂等可重入。
 */
class ServiceKeepAlive(context: Context) {

    private val appContext = context.applicationContext
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        if (cpuLock == null) {
            val pm = appContext.getSystemService(PowerManager::class.java)
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = appContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, WIFI_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    fun release() {
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private companion object {
        const val WAKE_TAG = "androidcontainer:fgs-cpu"
        const val WIFI_TAG = "androidcontainer:fgs-net"
    }
}
