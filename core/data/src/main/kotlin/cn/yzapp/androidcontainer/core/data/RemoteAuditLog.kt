package cn.yzapp.androidcontainer.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 远程控制审计日志（m8_m9 §2.2）：启停/删除/拉取等写操作的环形记录，容量 200。
 * 内存态——App 进程被杀即清空，属可接受的取舍（进程审计无跨进程持久化诉求）。
 */
object RemoteAuditLog {

    data class Entry(
        val timeMs: Long,
        val source: String,
        val action: String,
        val detail: String,
        val success: Boolean,
    )

    private const val CAPACITY = 200
    private val mutex = Mutex()
    private val entries = ArrayDeque<Entry>(CAPACITY)

    suspend fun record(source: String, action: String, detail: String, success: Boolean = true) {
        mutex.withLock {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(System.currentTimeMillis(), source, action, detail, success))
        }
    }

    suspend fun recent(max: Int = CAPACITY): List<Entry> = mutex.withLock {
        entries.toList().takeLast(max).asReversed()
    }

    suspend fun clear() = mutex.withLock { entries.clear() }
}
