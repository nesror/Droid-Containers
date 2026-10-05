package cn.yzapp.androidcontainer.core.engine.terminal

/**
 * libpty.so 的 JNI 入口（源码 `core/engine/src/main/cpp/pty.c`）。
 *
 * 句柄是 native 侧 session_t 指针；生命周期 open → read/write/resize → kill → close。
 * 只在真机/模拟器可用：宿主 JVM 单测不加载本库（相关逻辑测试走 [ReplayBuffer] 等纯 Kotlin 部分）。
 */
object PtyJni {

    init {
        System.loadLibrary("pty")
    }

    /** 创建 pty 会话并在子进程中 execv [argv]（返回 native 句柄；失败抛 IOException）。 */
    external fun open(cols: Int, rows: Int, argv: Array<String>, env: Array<String>): Long

    /** 读 master：返回字节数；0 = EOF（slave 关闭）；-1 = 错误。阻塞。 */
    external fun read(handle: Long, buf: ByteArray): Int

    /** 写 master：阻塞式全量单次 write；返回写入字节数，-1 = 错误（EPIPE 等）。 */
    external fun write(handle: Long, buf: ByteArray, len: Int): Int

    /** 设置窗口大小（TIOCSWINSZ，向前台进程组投递 SIGWINCH）。 */
    external fun resize(handle: Long, cols: Int, rows: Int)

    /** 非阻塞探测子进程退出码：未退出返回 -1。 */
    external fun exitCode(handle: Long): Int

    /** SIGKILL 整个进程组。 */
    external fun kill(handle: Long)

    /** 收尸 + 关 master + 释放句柄。幂等（句柄置空后再次调用为 no-op）。 */
    external fun close(handle: Long)
}
