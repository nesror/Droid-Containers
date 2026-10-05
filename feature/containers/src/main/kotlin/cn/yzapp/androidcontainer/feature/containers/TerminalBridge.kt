package cn.yzapp.androidcontainer.feature.containers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.engine.terminal.TerminalSession
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 终端页桥（WebView xterm.js ↔ 引擎 TerminalSession）：
 * - 输入：JS base64 → [writeB64] → 会话 stdin；
 * - 输出：会话 output 流 → 30ms 批量 base64 → `window.__termWrite`；
 * - 直连引擎不经 HTTP，远程控制开关/门禁均不影响 App 内终端。
 *
 * 生命周期由 TerminalViewModel 持有（旋转后复用）：页面重载触发 [onReady] 时，
 * 若会话已在跑则对新 WebView 回放历史输出，不重复开会话。
 */
class TerminalBridge(private val containerId: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var webView: WebView? = null

    /** 会话由 JavaBridge 线程读、IO 协程写 → 必须 @Volatile（审查 P1-18）。 */
    @Volatile
    private var session: TerminalSession? = null

    /** 首次建会话的单飞闸（审查 P1-18）：避免并发 onReady 开出两个 proot 会话。 */
    private val openLock = Any()

    @Volatile
    private var opening = false

    // 攒原始字节，flush 时一次性编码：绝不能把多段各自带 '=' padding 的 base64
    // 字符串首尾拼接（JS atob 对中间 padding 直接抛 InvalidCharacterError，
    // 整批输出会被丢弃——2026-09-24 真机日志里终端桥反复报 atob 错误即此因）。
    private val pending = ByteArrayOutputStream()

    @Volatile
    private var flushScheduled = false

    /** 页面每次加载（含旋转重建）后由 JS 调用；重复调用只重新挂载 WebView 与回放。 */
    @JavascriptInterface
    fun onReady(cols: Int, rows: Int) {
        val existing = session
        if (existing != null) {
            // 回放编码放到协程（审查 P1-18）：最多 128KB 的复制 + base64 不能占 JavaBridge 线程
            scope.launch {
                val replay = b64(existing.drainReplay())
                main.post { webView?.evaluateJavascript("window.__termWrite('$replay')", null) }
            }
            return
        }
        // 单飞（审查 P1-18）：onReady 可能被并发调用（页面重载 / 旋转 / JS 重试），
        // 无互斥会开出两个 proot 会话 → 孤儿进程 + 重复输出 + 快速耗尽 MAX_SESSIONS
        synchronized(openLock) {
            if (opening || session != null) return
            opening = true
        }
        scope.launch {
            try {
                val s = DataGraph.containerRepository.openTerminal(containerId, cols, rows)
                session = s
                launch { s.output.collect { enqueue(it) } }
                launch { s.exit.collect { code -> postJs("window.__termExit($code)") } }
            } catch (e: Exception) {
                val msg = (e.message ?: "terminal open failed").replace("'", "\\'")
                postJs("window.__termError('${b64("terminal: $msg\n".toByteArray())}')")
            } finally {
                opening = false
            }
        }
    }

    /** 键盘输入（xterm onData 的 UTF-8 字节，base64 转运避免引号转义问题）。 */
    @JavascriptInterface
    fun writeB64(b64: String) {
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
        scope.launch { session?.write(bytes) }
    }

    /**
     * 终端尺寸变化。⚠️ 必须异步派发：@JavascriptInterface 跑在 WebView 的 JavaBridge
     * 线程上，若在此同步等待会话锁（resize 可能与 write 排队），JavaBridge Looper
     * 被冻结 → renderer 的 JS↔Java 通道整体挂起 → 终端整页黑屏、键盘弹不出
     * （2026-09-24 真机复现）。onReady/writeB64 本就经 scope 派发，此处对齐。
     */
    @JavascriptInterface
    fun resize(cols: Int, rows: Int) {
        scope.launch { session?.resize(cols, rows) }
    }

    fun attach(view: WebView) {
        webView = view
    }

    fun destroy() {
        scope.cancel()
        val s = session
        session = null
        // 会话关闭涉及 waitpid/join，别占主线程
        Thread { s?.close() }.apply { isDaemon = true }.start()
        webView = null
    }

    private fun enqueue(chunk: ByteArray) {
        synchronized(pending) { pending.write(chunk) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        main.postDelayed({
            flushScheduled = false
            val payload = synchronized(pending) {
                if (pending.size() == 0) return@postDelayed
                val bytes = pending.toByteArray()
                pending.reset()
                b64(bytes)
            }
            if (payload.isNotEmpty()) postJs("window.__termWrite('$payload')")
        }, FLUSH_INTERVAL_MS)
    }

    private fun postJs(js: String) {
        main.post { webView?.evaluateJavascript(js, null) }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val FLUSH_INTERVAL_MS = 30L
    }
}
