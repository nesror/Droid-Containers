package cn.yzapp.androidcontainer.core.data

import android.content.Context
import android.net.ConnectivityManager

/**
 * 容器 DNS 解析（/etc/resolv.conf 内容来源）。
 *
 * 背景：旧版默认 DNS 硬编码 1.1.1.1，在部分运营商网络中发往境外 53 端口的 UDP 会被
 * 直接拒收（ICMP unreachable），容器内 Go 程序报
 * `lookup xxx on 1.1.1.1:53: read udp ...: read: connection refused`
 * （2026-10 用户反馈：alist 添加夸克网盘失败）。现默认跟随 Android 系统当前网络的
 * DNS（通常为网关/运营商，对国内外域名均可达），用户可在 设置 → 容器设置 显式覆盖；
 * 显式值支持逗号/分号/空白分隔的多个服务器。
 */
object ContainerDns {

    /** resolv.conf MAXNS 上限。 */
    const val MAX_SERVERS = 3

    /** 系统 DNS 不可得时的兜底（AliDNS anycast 全球可达；1.1.1.1 作补充）。 */
    val FALLBACK_SERVERS = listOf("223.5.5.5", "1.1.1.1")

    private val SEPARATOR = Regex("[,;\\s]+")

    private fun String.isUsableServer(): Boolean =
        isNotEmpty() && !startsWith("127.") && this != "::1"

    /**
     * 由「用户设置 + 系统 DNS」得出容器 nameserver 列表。
     * 用户显式设置优先（滤回环地址，截到 [MAX_SERVERS]）；为空跟随系统；
     * 系统 DNS 全被滤掉/不可得时用 [FALLBACK_SERVERS]。
     */
    fun serversFromSetting(setting: String, systemServers: List<String>): List<String> {
        val explicit = setting.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isUsableServer() }
        if (explicit.isNotEmpty()) return explicit.take(MAX_SERVERS)
        val system = systemServers
            .map { it.trim() }
            .filter { it.isUsableServer() }
            // IPv4 优先、IPv6 兜底（sortedBy 稳定，同组保持系统原序）
            .sortedBy { if (it.contains(':')) 1 else 0 }
        return system.take(MAX_SERVERS).ifEmpty { FALLBACK_SERVERS }
    }

    /** 生成 resolv.conf 正文（每行一个 nameserver）。 */
    fun resolvConfContent(servers: List<String>): String =
        servers.take(MAX_SERVERS).joinToString("") { "nameserver $it\n" }
}

/** 读取 Android 系统当前活跃网络的 DNS 服务器（无需权限）。 */
class SystemDnsReader(private val context: Context) {

    fun currentServers(): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val lp = cm.getLinkProperties(network) ?: return emptyList()
        return runCatching {
            lp.dnsServers.mapNotNull { it.hostAddress?.takeIf { addr -> addr.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }
}
