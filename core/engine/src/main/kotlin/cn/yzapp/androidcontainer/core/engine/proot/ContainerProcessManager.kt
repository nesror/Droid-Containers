package cn.yzapp.androidcontainer.core.engine.proot

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * 容器进程树管理。
 *
 * 停止顺序是关键坑（方案 §3.4）：proot 5.1.107 忽略 SIGTERM/SIGINT，
 * Process.destroy() 无效；仅 SIGKILL proot 会让容器进程成孤儿继续占端口。
 * 正确顺序：先对 proot 的【直接子进程】SIGTERM → 8 秒未退对残余 SIGKILL → 最后 SIGKILL proot。
 * 顺序不能颠倒。
 */
class ContainerProcessManager(
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun stopProcessTree(prootPid: Int, graceMs: Long = 8000L): Unit = withContext(ioDispatcher) {
        val children = directChildrenOf(prootPid)
        // 1) 先子后父：SIGTERM 所有直接子进程
        children.forEach { sendSignal(it, OsConstants.SIGTERM) }

        // 2) 宽限期内等待退出
        val deadline = System.currentTimeMillis() + graceMs
        var survivors = children
        while (System.currentTimeMillis() < deadline) {
            survivors = survivors.filter { isAlive(it) }
            if (survivors.isEmpty()) break
            delay(200)
        }

        // 3) 残余进程 SIGKILL，随后收 proot 本体
        survivors.forEach { sendSignal(it, OsConstants.SIGKILL) }
        if (isAlive(prootPid)) {
            sendSignal(prootPid, OsConstants.SIGKILL)
        }
    }

    private fun sendSignal(pid: Int, signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (_: Exception) {
            // 进程已退出，忽略
        }
    }

    private fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    internal fun directChildrenOf(pid: Int): List<Int> {
        val children = mutableListOf<Int>()
        val procDir = File("/proc")
        val numericDirs = procDir.listFiles { f -> f.name.toIntOrNull() != null } ?: return children
        for (dir in numericDirs) {
            val stat = File(dir, "stat")
            val ppid = stat.takeIf { it.isFile }?.let { parsePpid(it.readText()) } ?: continue
            if (ppid == pid) children.add(dir.name.toInt())
        }
        return children
    }

    internal companion object {
        /**
         * /proc/<pid>/stat 第 4 字段是 ppid。
         * 注意 comm 字段（第 2 个）可能含空格与括号，必须取最后一个 ')' 之后的剩余部分再切。
         */
        internal fun parsePpid(stat: String): Int? {
            val afterComm = stat.substringAfterLast(')')?.trim() ?: return null
            // afterComm 以 state 开头：state ppid ...
            val fields = afterComm.split(' ').filter { it.isNotEmpty() }
            // fields[0] = state, fields[1] = ppid
            return fields.getOrNull(1)?.toIntOrNull()
        }
    }
}
