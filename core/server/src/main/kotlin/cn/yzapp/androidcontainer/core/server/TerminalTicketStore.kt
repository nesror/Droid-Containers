package cn.yzapp.androidcontainer.core.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端 WebSocket 一次性票据（审查 P1-4）：浏览器 WebSocket 无法携带自定义 header，
 * 原方案把长期 Bearer token 放进 `?token=` 查询串——会进服务器/代理日志与浏览器历史。
 * 改为两段式：先用 Bearer 调 `POST /api/v1/terminal-ticket` 换一次性短时票据，
 * WS 升级时只出示票据（60 秒有效、单次消费）。
 */
internal object TerminalTicketStore {

    private const val TTL_MS = 60_000L

    private data class Ticket(val expiresAt: Long)

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val random = SecureRandom()

    /** 签发一张 128-bit 随机票据，TTL 内单次有效。 */
    fun issue(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val ticket = bytes.joinToString("") { "%02x".format(it) }
        tickets[ticket] = Ticket(System.currentTimeMillis() + TTL_MS)
        // 顺手清过期，防长驻进程缓慢累积
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
        return ticket
    }

    /** 消费一张票据：有效（存在且未过期）返回 true 并立即作废；其余一律 false。 */
    fun consume(ticket: String?): Boolean {
        if (ticket.isNullOrBlank()) return false
        val entry = tickets.remove(ticket) ?: return false
        return entry.expiresAt >= System.currentTimeMillis()
    }
}
