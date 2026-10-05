package cn.yzapp.androidcontainer.core.engine.oci

import cn.yzapp.androidcontainer.core.common.DefaultDispatcherProvider
import cn.yzapp.androidcontainer.core.common.DispatcherProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 镜像源测速：探测各 registry 的 `/v2/` 端点并返回 RTT（连接 + TLS + 首响应）。
 *
 * `/v2/` 是 Docker Registry HTTP API 的探活端点：未鉴权时返回 401（含 WWW-Authenticate），
 * 任何可达的 registry（含 1ms.run / daocloud 等代理）都会快速应答——
 * 401 即「可达」，耗时即「到首字节的成本」，与 PullEngine 首个请求同路径，测的就是真实拉取入口。
 */
object MirrorProbe {

    data class Result(val host: String, /** RTT 毫秒；null = 不可达/超时。 */ val latencyMs: Long?)

    suspend fun probe(host: String, timeoutMs: Long = 5_000L, dispatchers: DispatcherProvider = DefaultDispatcherProvider()): Result =
        withContext(dispatchers.io) {
            val url = if (host.startsWith("http://") || host.startsWith("https://")) {
                "${host.trimEnd('/')}/v2/"
            } else {
                "https://$host/v2/"
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val start = System.nanoTime()
            val latency = try {
                client.newCall(Request.Builder().url(url).head().build()).execute().use { _ ->
                    System.nanoTime() - start
                }
            } catch (_: Exception) {
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            Result(host = host, latencyMs = latency?.let { it / 1_000_000 })
        }

    /** 并发测多个源；失败（null）排序时沉底，同组保持原顺序。 */
    suspend fun probeAll(hosts: List<String>, timeoutMs: Long = 5_000L): List<Result> =
        withContext(DefaultDispatcherProvider().io) {
            hosts.map { host -> async { probe(host, timeoutMs) } }.awaitAll()
        }
}
