package cn.yzapp.androidcontainer.core.engine.proot

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 正在运行的 proot 实例。 */
data class RunningProcess(
    val pid: Int,
    val process: Process,
    val logFile: File,
)

/**
 * 容器进程管理：proot 进程启动/停止/状态（方案 §3.4）。
 *
 * 关键行为：
 * - 日志：每次启动**重写** runs/<containerId>.log（Redirect.to 截断模式，
 *   对应方案"日志内容为每次启动重写的完整行列表"）；
 * - stdout/stderr 合流写入同一日志；
 * - 停止：先子后父（SIGTERM → 宽限 → SIGKILL → 收 proot）；
 * - 进程表仅存内存（App 进程被杀即失效，恢复由用户重启容器，M5 再做 pid 持久化）。
 */
class ContainerManager(
    private val engineDir: File,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val processManager = ContainerProcessManager(ioDispatcher)
    private val running = ConcurrentHashMap<String, RunningProcess>()

    /** 进程自行退出（非 stop 触发）时回调，供上层同步运行状态；进程表清理后触发。 */
    @Volatile
    var onProcessExit: ((containerId: String) -> Unit)? = null

    fun isRunning(containerId: String): Boolean = running.containsKey(containerId)

    fun runningIds(): Set<String> = running.keys.toSet()

    fun pidOf(containerId: String): Int? = running[containerId]?.pid

    fun logFile(containerId: String): File = File(engineDir, "runs/$containerId.log")

    /**
     * 启动 proot 容器进程。返回 pid；进程退出后自动从运行表中移除。
     */
    suspend fun start(
        containerId: String,
        argv: List<String>,
        environment: Map<String, String>,
    ): Int = withContext(ioDispatcher) {
        if (running.containsKey(containerId)) {
            throw EngineException(EngineErrorCode.START_FAILED, "container $containerId already running")
        }
        val logFile = logFile(containerId)
        logFile.parentFile?.mkdirs()
        // 每次启动重写日志（截断）：同步截断，保证 start 返回后 readLog 不会读到上一轮内容
        java.io.RandomAccessFile(logFile, "rws").use { it.setLength(0) }

        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        builder.environment().putAll(environment)
        // 输出走 tee 泵线程而非 Redirect.to：stdout/stderr 合流后同时写日志文件与 logcat，
        // 方便 adb logcat 直接看容器/进程报错（2026-09-30 起）

        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw EngineException(EngineErrorCode.START_FAILED, "exec proot failed: ${e.message}", e)
        }
        val pid = processPid(process)
        startOutputPump(containerId, process, logFile)

        val proc = RunningProcess(pid, process, logFile)
        running[containerId] = proc

        // 后台监听退出：清表（不阻塞调用方）。
        // 用 remove(key, value) 条件删除判定"这次退出由谁记账"：
        // - 自然退出：监视线程删表成功 → 触发 onProcessExit（上层同步 DB + 发 die）；
        // - stop() 主动停止：stop 已先把表项删掉 → 监视线程删表失败 → 不回调（避免重复 die）。
        Thread {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
            } finally {
                if (running.remove(containerId, proc)) {
                    onProcessExit?.invoke(containerId)
                }
            }
        }.apply { isDaemon = true }.start()

        pid
    }

    /**
     * 输出泵：把 proot 进程的合并 stdout/stderr 同时写入 [logFile] 与 logcat。
     * 按行送 logcat（tag `ACM/Cnt/<id 前 8 位>`），完整字节流落文件（docker logs 等价物）；
     * 换行前不解码，避免多字节 UTF-8 序列被 read 边界切断产生乱码。
     * 进程退出（含 stop 杀进程）时流 EOF，泵线程自然结束。
     */
    private fun startOutputPump(containerId: String, process: Process, logFile: File) {
        Thread {
            try {
                java.io.FileOutputStream(logFile, true).use { out ->
                    process.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        val pending = java.io.ByteArrayOutputStream()
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            for (i in 0 until n) {
                                if (buf[i].toInt() == '\n'.code) {
                                    logcatLine(containerId, pending.toString("UTF-8"))
                                    pending.reset()
                                } else {
                                    pending.write(buf[i].toInt())
                                }
                            }
                        }
                        if (pending.size() > 0) logcatLine(containerId, pending.toString("UTF-8"))
                    }
                }
            } catch (_: Exception) {
                // 泵线程故障不影响容器运行；文件可能缺尾部日志，可接受
            }
        }.apply {
            isDaemon = true
            name = "acm-container-log-$containerId"
        }.start()
    }

    private fun logcatLine(containerId: String, line: String) {
        if (line.isBlank()) return
        try {
            android.util.Log.println(
                android.util.Log.INFO,
                "ACM/Cnt/${containerId.take(8)}",
                line,
            )
        } catch (_: Exception) {
            // logcat 不可用（如单测环境）时静默
        }
    }

    /**
     * 停止容器：先子后父（SIGTERM 子进程 → 8s 宽限 → SIGKILL → 收 proot）。
     * 幂等：容器已退出（进程表无记录）时视为已停止，直接返回不抛错。
     * 顺序不能颠倒（proot 忽略 SIGTERM，先杀 proot 会留孤儿）。
     */
    suspend fun stop(containerId: String, graceMs: Long = 8000L) = withContext(ioDispatcher) {
        val proc = running[containerId] ?: return@withContext
        try {
            processManager.stopProcessTree(proc.pid, graceMs)
        } finally {
            // 条件删除：若进程恰好自然退出、监视线程已删表并触发 onProcessExit，
            // 这里不会误删（可能来自重新 start 的）新表项
            running.remove(containerId, proc)
            proc.process.destroy()
        }
    }

    /** 读取当前日志（每次启动重写后的完整行列表）。 */
    fun readLog(containerId: String): List<String> {
        val file = logFile(containerId)
        return if (file.isFile) file.readLines() else emptyList()
    }

    /** 日志增量读取结果：nextOffset 供下次续读（字节偏移，非行号）。 */
    data class LogChunk(val nextOffset: Long, val lines: List<String>)

    /**
     * 从字节偏移 [byteOffset] 续读日志（M8 日志 follow 的地基）。
     * 末行不完整（无换行符）时不返回它，nextOffset 停在该行行首，避免半行重复。
     */
    fun readLogFrom(containerId: String, byteOffset: Long): LogChunk {
        val file = logFile(containerId)
        if (!file.isFile) return LogChunk(byteOffset.coerceAtLeast(0), emptyList())
        val length = file.length()
        if (byteOffset >= length) return LogChunk(length, emptyList())
        // 单次最多读 4MB（审查 P1-8）：日志文件可被无限增长，整文件入内存有 OOM 面；
        // nextOffset 语义不变——调用方以偏移续读即可拿全量
        val readLen = minOf(length - byteOffset, MAX_LOG_CHUNK_BYTES)
        val bytes = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(byteOffset)
            ByteArray(readLen.toInt()).also { raf.readFully(it) }
        }
        val text = String(bytes, Charsets.UTF_8)
        val lastNewline = text.lastIndexOf('\n')
        if (lastNewline < 0) return LogChunk(byteOffset, emptyList())
        val complete = text.substring(0, lastNewline)
        return LogChunk(
            nextOffset = byteOffset + lastNewline + 1,
            lines = complete.split('\n').filter { it.isNotEmpty() || complete.length > 1 },
        )
    }

    /**
     * exec：向运行中容器的 sh stdin 写入一行命令（输入重定向方案，方案 §3.4）。
     * stdout/stderr 已合流写入运行日志，调用方通过 [readLog] 轮询输出。
     * 无 pty：无提示符回显，但命令执行与输出完整。
     */
    fun exec(containerId: String, command: String) {
        val proc = running[containerId]
            ?: throw EngineException(EngineErrorCode.START_FAILED, "container $containerId is not running")
        try {
            proc.process.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
            proc.process.outputStream.flush()
        } catch (e: java.io.IOException) {
            throw EngineException(EngineErrorCode.START_FAILED, "exec write failed: ${e.message}", e)
        }
    }

    internal companion object {
        /** 单次日志读取上限（字节）：防日志无限增长撑爆内存（审查 P1-8）。 */
        const val MAX_LOG_CHUNK_BYTES = 4L * 1024 * 1024

        /**
         * 取子进程 pid：java.lang.Process#pid() 在部分 android.jar API surface
         * 不可见，反射兜底（Android 实现为 long）。
         */
        fun processPid(process: Process): Int = try {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(process) as Long).toInt()
        } catch (_: Exception) {
            try {
                val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
                field.getInt(process)
            } catch (e: Exception) {
                throw EngineException(EngineErrorCode.START_FAILED, "cannot obtain proot pid", e)
            }
        }
    }
}
