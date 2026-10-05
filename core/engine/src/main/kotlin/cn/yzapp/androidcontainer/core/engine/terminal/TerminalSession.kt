package cn.yzapp.androidcontainer.core.engine.terminal

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 容器终端会话：独立 proot shell 进程 + 真 PTY（等价 `docker exec -it <c> sh`）。
 *
 * 与运行容器进程（ContainerManager）互不干扰——rootfs 只是宿主目录树，新起一个
 * proot 进程挂同一 rootfs 即可；容器停止/删除时会话由上层显式关闭。
 *
 * 输出通道：读线程阻塞读 master → [output] SharedFlow（实时）+ [ReplayBuffer]
 * （断线重连回放，上限 [REPLAY_LIMIT_BYTES]）。退出后 [exit] 发一次退出码。
 */
class TerminalSession(
    val id: String,
    val containerId: String,
    private val handle: Long,
    private val ioDispatcher: CoroutineDispatcher,
    private val onRemove: (TerminalSession) -> Unit,
) {

    private val _output = kotlinx.coroutines.flow.MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 实时输出（字节即 pty 原始流，ANSI/UTF-8 由前端终端渲染）。 */
    val output = _output

    private val _exit = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val exit = _exit

    private val replay = ReplayBuffer(REPLAY_LIMIT_BYTES)

    @Volatile
    private var readerAlive = true

    /**
     * 句柄已失效（close 释放 native 会话后置位）。快速调用（write/resize）必须经
     * [lockedCall] 在锁内校验——否则并发关闭会出现 double-free / UAF
     * （2026-09-24 真机 Scudo "invalid chunk state" 闪退即此因：页面关闭与容器停止
     * 并发各走一次 PtyJni.close）。
     *
     * ⚠️ 锁纪律：nativeLock 是短锁——**绝不在锁内做阻塞 I/O**。read/exitCode 这类
     * 长阻塞调用不上锁（由 close 的 reader.join 保证与 free 互斥）；否则阻塞的 read
     * 持锁会让 JavaBridge（WebView JS→Java 桥）等锁冻结，renderer 跟着挂起，
     * 表现为终端整页黑屏、键盘弹不出（2026-09-24 真机复现）。
     */
    @Volatile
    private var closed = false

    /** close 单次执行闸门（native free 不允许发生两次）。 */
    private val closeOnce = AtomicBoolean(false)

    /** 串行化短调用（write/resize/close 的释放），与 close 的 free 互斥。 */
    private val nativeLock = Any()

    private val reader = Thread({ readLoop() }, "terminal-$id").apply { isDaemon = true }

    /** 锁内校验 [closed] 后执行**瞬时**原生调用；句柄已释放则返回 null，绝不触碰内存。 */
    private fun <T> lockedCall(block: () -> T): T? =
        synchronized(nativeLock) {
            if (closed) null else block()
        }

    /** 读线程启动（与构造分离，避免 this 逃逸）。 */
    fun start() {
        reader.start()
    }

    private fun readLoop() {
        val buf = ByteArray(READ_CHUNK)
        while (readerAlive) {
            if (closed) break
            // read 是长阻塞调用，不持 nativeLock（锁纪律见 closed 注释）；
            // 与 close 的互斥由 close 里 join(reader) 保证：读线程不结束就不释放。
            val n = PtyJni.read(handle, buf)
            if (n > 0) {
                val chunk = buf.copyOf(n)
                replay.append(chunk)
                _output.tryEmit(chunk)
            } else {
                break // EOF（0）或错误（-1，多为 master 已关）
            }
        }
        readerAlive = false
        // 子进程退出后 waitpid 可能还没来得及收尸：短暂重试拿到真实退出码
        var code = -1
        var waited = 0L
        while (waited < EXIT_POLL_MS) {
            if (closed) break
            code = PtyJni.exitCode(handle)
            if (code >= 0) break
            Thread.sleep(EXIT_POLL_INTERVAL)
            waited += EXIT_POLL_INTERVAL
        }
        _exit.tryEmit(code)
    }

    /** 向会话写输入（键盘字节流）。会话已关闭时静默丢弃。 */
    suspend fun write(bytes: ByteArray) = withContext(ioDispatcher) {
        lockedCall { PtyJni.write(handle, bytes, bytes.size) }
        Unit
    }

    fun resize(cols: Int, rows: Int) {
        if (cols in 2..500 && rows in 2..300) lockedCall { PtyJni.resize(handle, cols, rows) }
    }

    /** 回放缓冲快照（重连时先发历史输出）。 */
    fun drainReplay(): ByteArray = replay.snapshot()

    /**
     * 关闭：SIGKILL 进程组 → 等读线程收尾（kill 让阻塞的 read 以 EIO 返回，最多 3s）
     * → 锁内置失效标志 + native 收尸释放。幂等（CAS 保证只执行一次）。并发调用安全。
     *
     * join 是 read/exitCode 与 free 的互斥点：读线程（阻塞在 read 上）不结束绝不释放；
     * write/resize 这类短调用则由 nativeLock 与释放互斥。
     */
    fun close() {
        if (!closeOnce.compareAndSet(false, true)) return
        runCatching { PtyJni.kill(handle) }
        runCatching { reader.join(CLOSE_JOIN_MS) }
        synchronized(nativeLock) {
            closed = true
            runCatching { PtyJni.close(handle) }
        }
        onRemove(this)
    }

    companion object {
        private const val READ_CHUNK = 8 * 1024
        private const val REPLAY_LIMIT_BYTES = 128 * 1024
        private const val EXIT_POLL_MS = 2000L
        private const val EXIT_POLL_INTERVAL = 25L
        private const val CLOSE_JOIN_MS = 3000L
    }
}

/**
 * 终端会话表：按 id 管理，限制总量（防泄漏的 proot 进程堆积）。
 * 超限时先关最早打开的会话再放行新会话。
 */
class TerminalSessionManager {

    private val sessions = LinkedHashMap<String, TerminalSession>()

    val count: Int get() = synchronized(sessions) { sessions.size }

    fun get(sessionId: String): TerminalSession? = synchronized(sessions) { sessions[sessionId] }

    fun sessionsOf(containerId: String): List<TerminalSession> = synchronized(sessions) {
        sessions.values.filter { it.containerId == containerId }
    }

    /**
     * 打开新会话（启动读线程）。[argv]/[env] 由上层组装（proot 命令行与注入环境）。
     * 超过 [MAX_SESSIONS] 时抛错（前端提示关掉旧终端，而不是悄悄杀别人的会话）。
     */
    fun open(
        containerId: String,
        argv: List<String>,
        env: Map<String, String>,
        cols: Int,
        rows: Int,
        ioDispatcher: CoroutineDispatcher,
    ): TerminalSession = synchronized(sessions) {
        if (sessions.size >= MAX_SESSIONS) {
            throw EngineException(
                EngineErrorCode.START_FAILED,
                "too many terminal sessions (max $MAX_SESSIONS)",
            )
        }
        val handle = PtyJni.open(
            cols.coerceIn(2, 500),
            rows.coerceIn(2, 300),
            argv.toTypedArray(),
            env.map { "${it.key}=${it.value}" }.toTypedArray(),
        )
        if (handle == 0L) {
            throw EngineException(EngineErrorCode.START_FAILED, "pty open failed")
        }
        val session = TerminalSession(
            id = UUID.randomUUID().toString(),
            containerId = containerId,
            handle = handle,
            ioDispatcher = ioDispatcher,
            onRemove = { synchronized(sessions) { sessions.remove(it.id) } },
        )
        sessions[session.id] = session
        session.start()
        session
    }

    /** 关闭某容器的全部会话（容器停止/删除时调用）。 */
    fun closeForContainer(containerId: String) {
        val toClose = sessionsOf(containerId)
        toClose.forEach { runCatching { it.close() } }
    }

    fun closeAll() {
        synchronized(sessions) { sessions.values.toList() }.forEach { runCatching { it.close() } }
    }

    companion object {
        const val MAX_SESSIONS = 8
    }
}

/** 环形回放缓冲：截断时丢最旧字节，保持整段 ≤ 上限。 */
class ReplayBuffer(private val limitBytes: Int) {

    private var data = ByteArrayOutputStream()

    @Synchronized
    fun append(chunk: ByteArray) {
        data.write(chunk)
        if (data.size() > limitBytes) {
            val all = data.toByteArray()
            val kept = all.copyOfRange(all.size - limitBytes, all.size)
            data = ByteArrayOutputStream(limitBytes)
            data.write(kept, 0, kept.size)
        }
    }

    @Synchronized
    fun snapshot(): ByteArray = data.toByteArray()
}
