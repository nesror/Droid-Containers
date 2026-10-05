package cn.yzapp.androidcontainer.core.engine.compose

import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 端口就绪探测（compose up 的 depends_on「启动顺序 + 就绪等待」地基）。
 *
 * proot 与手机共享宿主网络栈：容器内监听的端口在手机上就是 `127.0.0.1:<port>`，
 * App 进程内直接 TCP connect 即等价于「服务已开口」，无需 HTTP 层（被依赖方多为
 * db/mqtt 等 TCP 服务，握手成功即足以让依赖方连上；HTTP 语义留给依赖方自己处理）。
 *
 * 纯 JVM 实现（java.net.Socket），单测可用 ServerSocket 直接覆盖。
 */
object ReadinessProbe {

    /** 默认就绪等待窗口：覆盖 mosquitto/redis/mariadb 等常规依赖的首启时间。 */
    const val DEFAULT_TIMEOUT_MS: Long = 120_000L

    fun isPortOpen(port: Int, timeoutMs: Int = 500): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 等待任一端口就绪；返回首个就绪的端口，超时返回 null。
     * [delayFn] 仅为测试注入（默认协程 delay），生产调用方无需关心。
     */
    suspend fun awaitPortOpen(
        ports: List<Int>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        intervalMs: Long = 1_000L,
        delayFn: suspend (Long) -> Unit = { delay(it) },
    ): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            ports.firstOrNull { isPortOpen(it) }?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            delayFn(intervalMs)
        }
    }
}
