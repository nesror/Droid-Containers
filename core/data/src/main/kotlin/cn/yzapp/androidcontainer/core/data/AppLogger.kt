package cn.yzapp.androidcontainer.core.data

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 应用日志（问题反馈 §导出）：内存环形缓冲 + filesDir/logs/app.log 持久化。
 * - 文件滚动：超过 [MAX_FILE_BYTES] 时改名 app.log.old 覆盖旧档，只保留一档历史。
 * - 写失败静默忽略：日志系统自身故障不允许影响业务。
 * - 导出：合并 app.log.old + app.log，附加设备与应用信息头，写入调用方指定目录。
 *
 * 线程模型（审查 P1-10）：
 * - 调用线程只做「格式化 + 入内存缓冲 + 入队」，**不做任何文件 IO**，避免调用线程
 *   （含主线程）被 `appendText` + 滚动 rename 阻塞；
 * - 单条后台线程串行消费队列落盘，与 [exportTo] 共用同一把锁，保证导出看到完整行；
 * - 时间格式化改用 [java.time.format.DateTimeFormatter]（不可变、线程安全），替换原先
 *   在 `synchronized` 之外被并发调用的共享 `SimpleDateFormat`（非线程安全，并发下会
 *   产生错乱时间戳甚至 ArrayIndexOutOfBoundsException）。
 */
object AppLogger {

    private const val MAX_BUFFER = 2000
    private const val MAX_FILE_BYTES = 512L * 1024

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_BUFFER)

    /** 待落盘行队列：调用线程入队即返回，落盘由 [writer] 串行完成。 */
    private val queue = LinkedBlockingQueue<String>()

    private val timeFormat = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(java.time.ZoneId.systemDefault())

    @Volatile
    private var logFile: File? = null
    private var appVersionName: String = "?"
    private var appVersionCode: Long = -1

    private val writer: Thread = Thread {
        while (true) {
            val line = try {
                queue.take()
            } catch (_: InterruptedException) {
                continue
            }
            writeLine(line)
        }
    }.apply {
        isDaemon = true
        name = "acm-log-writer"
        start()
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val dir = File(appContext.filesDir, "logs").apply { mkdirs() }
            logFile = File(dir, "app.log")
            try {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                appVersionName = info.versionName ?: "?"
                appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } catch (_: Exception) {
                // 包信息读取失败不影响日志
            }
            i("App", "logger initialized, version=$appVersionName($appVersionCode)")
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)

    private enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        android.util.Log.println(
            when (level) {
                LogLevel.DEBUG -> android.util.Log.DEBUG
                LogLevel.INFO -> android.util.Log.INFO
                LogLevel.WARN -> android.util.Log.WARN
                LogLevel.ERROR -> android.util.Log.ERROR
            },
            "ACM/$tag",
            message,
        )
        val line = buildString {
            append(timeFormat.format(java.time.Instant.now()))
            append(' ').append(level.name.first()).append('/').append(tag).append(": ").append(message)
            if (throwable != null) {
                append(" | ").append(throwable.javaClass.simpleName).append(": ").append(throwable.message)
            }
            append('\n')
        }
        synchronized(lock) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
        // 非阻塞入队：异常场景下队列满则丢日志，绝不阻塞调用线程
        queue.offer(line)
    }

    /** 由 [writer] 单线程调用；与 [exportTo] 共用 [lock] 保证不读到半行。 */
    private fun writeLine(line: String) {
        synchronized(lock) {
            val file = logFile ?: return
            try {
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val old = File(file.parentFile, "app.log.old")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line)
            } catch (_: Exception) {
                // 日志落盘失败静默忽略
            }
        }
    }

    /**
     * 导出日志到 [dir]（一般传 cacheDir/exports）：设备/应用信息头 + app.log.old + app.log。
     * 无任何日志内容时返回 null。
     */
    fun exportTo(dir: File): File? {
        // 先等队列排空（最多 500ms），否则导出的文件会缺最后几行。必须在取锁**之前**等待，
        // 否则会与 writer 线程争锁
        val deadline = System.currentTimeMillis() + 500
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                break
            }
        }
        synchronized(lock) {
            val current = logFile?.takeIf { it.exists() && it.length() > 0 }
            val old = logFile?.parentFile?.let { parent -> File(parent, "app.log.old").takeIf { it.exists() } }
            if (current == null && old == null) return null
            dir.mkdirs()
            val out = File(dir, "acm-logs-${System.currentTimeMillis()}.txt")
            out.outputStream().use { outStream ->
                val writer = outStream.bufferedWriter()
                writer.appendLine("== Droid Containers diagnostics ==")
                writer.appendLine("time: ${timeFormat.format(java.time.Instant.now())}")
                writer.appendLine("version: $appVersionName ($appVersionCode)")
                writer.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                writer.appendLine()
                writer.flush()
                old?.inputStream()?.use { input -> input.copyTo(outStream) }
                current?.inputStream()?.use { input -> input.copyTo(outStream) }
            }
            return out
        }
    }
}
