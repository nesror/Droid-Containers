package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.billing.EntitlementRepository
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.ComposeRepository
import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.ImageRepository
import cn.yzapp.androidcontainer.core.data.RemoteAuditLog
import cn.yzapp.androidcontainer.core.data.SettingsRepository
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.websocket.WebSockets
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.readByteArray

/**
 * 远程控制 HTTP 服务（M8/M9）：
 * - Web 实例：`/api/v1` 前缀 REST（Bearer token 鉴权 + **模板包解锁门禁**）+ 控制台落地页 + `/_health` 免鉴权探活；
 * - Docker 实例：`/_ping` 与 `/v1.43` 前缀 Engine API（独立端口、无 header 鉴权、**不门禁**，m8_m9 §2.2/§7）。
 * 两个实例独立启停，端口绑定失败抛 IOException 由调用方（ServerService）提示。
 */
class RemoteHttpServer(
    private val images: ImageRepository,
    private val containers: ContainerRepository,
    private val compose: ComposeRepository,
    private val entitlement: EntitlementRepository,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ktor 引擎专用 scope：端口绑定失败除了从 start() 同步抛给调用方外，
     * CIO 内部的 accept 协程还会**异步**再抛一次同样的异常——它不在调用方的
     * 协程树里，没有处理器就会直接走到进程级 uncaughtExceptionHandler 闪退
     * （BindException 现网实锤）。这里挂上 SupervisorJob + 异常处理器兜底，
     * 只记日志、不影响其他引擎与宿主进程。
     */
    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            AppLogger.e("RemoteHttpServer", "engine internal failure", e)
        },
    )
    private var webEngine: EmbeddedServer<*, *>? = null
    private var dockerEngine: EmbeddedServer<*, *>? = null

    val isWebRunning: Boolean get() = webEngine != null
    val isDockerRunning: Boolean get() = dockerEngine != null

    /**
     * 启动 Web/API 实例（重复调用先停旧实例）。绑定失败抛异常。
     *
     * [token] 传 `StateFlow` 而非字符串（审查 P1-4）：鉴权中间件按请求读取当前值，
     * 用户在设置页「重置 token」后旧 token 立即失效，无需重启服务（原实现把 token
     * 按值捕获进闭包，重置后旧 token 仍长期有效，与 UI 文案不符）。
     */
    fun startWeb(port: Int, token: StateFlow<String?>) {
        stopWeb()
        val engine = engineScope.embeddedServer(CIO, host = "0.0.0.0", port = port) {
            webModule(token)
        }
        engine.start(wait = false)
        webEngine = engine
    }

    /**
     * 启动 Docker 兼容实例（独立端口）。绑定失败抛异常。
     *
     * [bindLan] = false 时只绑回环（默认，审查 P0-1）；= true 绑 0.0.0.0 供局域网
     * docker CLI 直连——前提是用户已在设置页显式二次确认（[SettingsRepository.dockerLanEnabled]），
     * 该端口本身无 header 鉴权，暴露面=同网段任意主机。
     */
    fun startDocker(port: Int, bindLan: Boolean = false) {
        stopDocker()
        val engine = engineScope.embeddedServer(CIO, host = if (bindLan) "0.0.0.0" else "127.0.0.1", port = port) {
            dockerModule()
        }
        engine.start(wait = false)
        dockerEngine = engine
    }

    fun stopWeb() {
        webEngine?.stop(500, 1000)
        webEngine = null
    }

    fun stopDocker() {
        dockerEngine?.stop(500, 1000)
        dockerEngine = null
    }

    fun stopAll() {
        stopWeb()
        stopDocker()
    }

    // ------------------------------------------------------------ modules

    private fun Application.webModule(token: StateFlow<String?>) {
        installAuth(token)
        installEntitlementGate()
        installBodyLimit(MAX_BODY_BYTES)
        installErrorMapping()
        // 帧上限 / 心跳 / 空闲超时（审查 P1-9 残余）：默认配置下恶意客户端可发超大帧，
        // 或建连后静默挂住不释放（泄漏一张票据即长期占用一个协程与连接）
        install(WebSockets) {
            // Ktor 3 的选项名是 *Millis（Long 毫秒），不是 Duration
            maxFrameSize = MAX_WS_FRAME_BYTES
            pingPeriodMillis = WS_PING_PERIOD_MS
            timeoutMillis = WS_TIMEOUT_MS
            masking = false
        }
        routing {
            // 控制台落地页免鉴权（token 由页面 JS 以 Bearer 携带，m8_m9 §3.2）：
            // 解锁状态在服务端注入，未解锁时页面自己渲染「需要解锁」，不再新开一个探状态的接口
            get("/") {
                val bytes = RemoteHttpServer::class.java.classLoader
                    ?.getResourceAsStream("web/index.html")?.readBytes()
                if (bytes == null) {
                    call.respondText(
                        "Console asset missing. API: /api/v1 (Bearer token required)",
                        io.ktor.http.ContentType.Text.Plain,
                    )
                    return@get
                }
                val state = entitlement.state.first()
                val language = TemplateFeed.resolveLanguage(
                    null,
                    call.request.headers[io.ktor.http.HttpHeaders.AcceptLanguage],
                )
                val gate = buildString {
                    append("{\"unlocked\":").append(EntitlementGate.isAllowed(state))
                    append(",\"state\":\"").append(EntitlementGate.stateName(state))
                    append("\",\"lang\":\"").append(language).append("\"}")
                }
                call.respondText(
                    String(bytes, Charsets.UTF_8).replace(GATE_PLACEHOLDER, gate),
                    io.ktor.http.ContentType.Text.Html,
                )
            }
            get("/_health") {
                call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
            }
            // 终端页静态资产（xterm.js 本地分发，零 CDN）；免鉴权 —— 只是 JS/CSS，无任何数据，
            // 终端数据通道由 WebSocket 路由自行鉴权 + 门禁
            get("/term/{file}") {
                val file = call.parameters["file"] ?: throw HttpNotFoundException("missing file")
                if (file !in TERM_ASSETS) throw HttpNotFoundException("no such asset: $file")
                val bytes = RemoteHttpServer::class.java.classLoader
                    ?.getResourceAsStream("web/term/$file")?.readBytes()
                    ?: throw HttpNotFoundException("no such asset: $file")
                val type = when {
                    file.endsWith(".js") -> io.ktor.http.ContentType.Text.JavaScript
                    file.endsWith(".css") -> io.ktor.http.ContentType.Text.CSS
                    else -> io.ktor.http.ContentType.Application.OctetStream
                }
                call.respondBytes(bytes, type)
            }
            route("/api/v1") {
                apiRoutes(images, containers, compose, entitlement, settings, scope)
                terminalRoutes(containers)
            }
        }
    }

    private fun Application.dockerModule() {
        installDockerOriginGuard()
        installBodyLimit(MAX_BODY_BYTES)
        installErrorMapping()
        routing {
            dockerApi(images, containers)
            dockerWriteRoutes(images, containers, scope)
            // 版本协商（m8_m9 §4.2-3）：声明 v1.43，但任意 /v1.4x 路径均可路由
            route("/v{apiVersion}") {
                dockerApi(images, containers)
            }
        }
    }

    /** Bearer token 中间件：api 与 Web 全路径强制校验，落地页与探活免鉴权（m8_m9 §3.1-4）。 */
    private fun Application.installAuth(token: StateFlow<String?>) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path == "/_health" || path == "/" || path.startsWith("/term/")) return@intercept
            // 每个请求读取**当前** token（审查 P1-4）：设置页「重置」后旧 token 立即失效，
            // 不再需要重启服务（原实现把 token 按值捕获进闭包，重置后旧值长期有效）
            val expected = token.value
            // 浏览器 WebSocket 无法携带自定义 header：终端通道改为一次性短时票据
            // `?ticket=`（先 POST /api/v1/terminal-ticket 用 Bearer 换取，审查 P1-4），
            // 长期 token 不再进 URL（日志/历史泄漏面）
            val viaTicket = path.matches(TERMINAL_WS_PATH) &&
                TerminalTicketStore.consume(call.request.queryParameters["ticket"])
            val authorized = viaTicket ||
                (expected != null && constantTimeEquals(
                    call.request.headers[io.ktor.http.HttpHeaders.Authorization],
                    "Bearer $expected",
                ))
            if (!authorized) {
                call.respondText(
                    """{"code":401,"message":"unauthorized"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
            }
        }
    }

    /**
     * 解锁门禁（m8_m9 方案 §7）：未解锁时 `/api/v1` 下的一切请求返回 `402`。
     *
     * 落地页与 `/_health` 不拦：前者要显示「需要解锁」，后者是探活（不该被业务门禁打断）。
     * 装在 [installAuth] 之后，所以未带 token 的请求先拿到 401——不向未鉴权者泄漏解锁状态。
     * **Docker 实例不装这个中间件**（对 docker CLI 的通用能力，产品上仍免费）。
     */
    private fun Application.installEntitlementGate() {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (!path.startsWith(API_PREFIX)) return@intercept
            if (EntitlementGate.isAllowed(entitlement.state.first())) return@intercept
            RemoteAuditLog.record("web", "denied", "${call.request.httpMethod.value} $path")
            call.respondText(
                """{"code":402,"message":"the remote console is part of the template pack"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.PaymentRequired,
            )
            finish()
        }
    }

    /**
     * Docker 实例的 CSRF 防线（审查 P0-1/P1-9）：本实例无 header 鉴权（docker CLI
     * 不支持自定义 header），跨站网页可直接向手机端口发简单请求驱动拉取/删除/执行。
     * 浏览器对跨站 POST/PUT/DELETE **必带** `Origin` 头，而 docker CLI、curl、
     * adb forward 代理从不带——带 Origin 一律 403 并记审计。
     */
    private fun Application.installDockerOriginGuard() {
        intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.headers[io.ktor.http.HttpHeaders.Origin] != null) {
                RemoteAuditLog.record("docker", "denied", "origin header present: ${call.request.httpMethod.value} ${call.request.path()}")
                call.respondText(
                    """{"code":403,"message":"origin not allowed"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.Forbidden,
                )
                finish()
            }
        }
    }

    /**
     * 请求体上限（审查 P1-7）：Ktor CIO 默认不限 body，`receiveText()` 全量入内存，
     * 无鉴权的 Docker 实例可用超大 body 打 OOM。这里按 `Content-Length` 预检拒绝；
     * **`Transfer-Encoding: chunked`（无长度）由 [receiveTextLimited] 在读取期按实际
     * 字节数兜底**——两层合起来才是不漏的上限，阈值 8MB 足够 compose YAML 与
     * docker create 的任意 JSON。
     */
    private fun Application.installBodyLimit(maxBytes: Long) {
        intercept(ApplicationCallPipeline.Plugins) {
            val length = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
            if (length != null && length > maxBytes) {
                call.respondText(
                    """{"code":413,"message":"request body too large"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
                finish()
            }
        }
    }

    /**
     * EngineException → HTTP 统一映射（m8_m9 §3.1-3）。
     * 错误响应会穿越到浏览器/第三方客户端，先脱敏（审查 P1-5）：
     * 引擎消息里可能携带宿主绝对路径（/data/user/0/<pkg>/files/…），暴露 App 私有目录结构。
     */
    private fun Application.installErrorMapping() {
        install(StatusPages) {
            // 读取期超限（chunked body 等无 Content-Length 的情形）：413，而非 500
            exception<RequestBodyTooLargeException> { call, _ ->
                call.respondError(HttpStatusCode.PayloadTooLarge, "request body too large")
            }
            exception<HttpNotFoundException> { call, cause ->
                call.respondError(HttpStatusCode.NotFound, scrubHostPaths(cause.message ?: "not found"))
            }
            exception<EngineException> { call, cause ->
                val status = when (cause.code) {
                    EngineErrorCode.COMPOSE_INVALID,
                    EngineErrorCode.IMAGE_MISSING,
                    EngineErrorCode.START_FAILED,
                    -> HttpStatusCode.BadRequest

                    else -> HttpStatusCode.InternalServerError
                }
                call.respondError(status, "[${cause.code}] ${scrubHostPaths(cause.message)}")
            }
            exception<Throwable> { call, _ ->
                // 未知异常不回显 cause.toString()（可能含路径/内部细节），只给类别
                call.respondError(HttpStatusCode.InternalServerError, "internal error, see device logcat")
            }
        }
    }
}

/** 受门禁保护的 REST 前缀。 */
private const val API_PREFIX = "/api/v1"

/** 请求体上限（字节）：防无界 body OOM（审查 P1-7）。 */
private const val MAX_BODY_BYTES = 8L * 1024 * 1024

/** WebSocket 单帧上限（审查 P1-9）：终端帧是按键与输出片段，1MB 足够。 */
private const val MAX_WS_FRAME_BYTES = 1L * 1024 * 1024

/** WebSocket 心跳与空闲超时（审查 P1-9）：避免建连后静默挂住长期占用连接与协程。 */
private const val WS_PING_PERIOD_MS = 30_000L
private const val WS_TIMEOUT_MS = 60_000L

/** 终端 WebSocket 路径（一次性票据 ?ticket= 仅此路径放行）。 */
private val TERMINAL_WS_PATH = Regex("/api/v1/containers/[^/]+/terminal")

/**
 * 带上限的请求体读取（审查 P1-7 收口）：`Content-Length` 预检挡不住
 * `Transfer-Encoding: chunked`，这里按**实际读入字节数**封顶，超出立即抛
 * [RequestBodyTooLargeException]（映射 413）。替换所有 `receiveText()` 调用点，
 * 避免无鉴权的 Docker 端口被超大 chunked body 打 OOM。
 */
internal suspend fun ApplicationCall.receiveTextLimited(maxBytes: Long = MAX_BODY_BYTES): String {
    // Ktor 3 的按量读取是扩展函数：`readBuffer(limit)` 至多返回 limit 字节，
    // 用 limit+1 探测"还有更多"，超出即判超限（readRemaining 已废弃）
    val bytes = receiveChannel().readBuffer(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) throw RequestBodyTooLargeException()
    return String(bytes, Charsets.UTF_8)
}

/** 请求体超过上限（读取期判定，映射 413）。 */
internal class RequestBodyTooLargeException : Exception("request body too large")

/** 常量时间比较（审查 P1-4）：避免逐字符短路泄漏 token 前缀长度信息。 */
private fun constantTimeEquals(a: String?, b: String): Boolean {
    if (a == null) return false
    return java.security.MessageDigest.isEqual(
        a.toByteArray(Charsets.UTF_8),
        b.toByteArray(Charsets.UTF_8),
    )
}

/**
 * 宿主绝对路径前缀（脱敏目标，审查 P1-5 / C-4）。
 * **只匹配宿主特有的目录形态**：App 私有目录（`/data/user/<n>`、`/data/data`、`/data/app`、
 * `/data/misc`）、共享存储（`/storage/...`）、桌面环境（macOS `/Users`、Windows 盘符）。
 * 早期版本把裸 `/data`、`/root`、`/home`、`/system`、`/vendor` 也计入，会把**容器内**的
 * `/root/.cache`、`/home/app` 一并替换成 `<host>`，损坏错误消息语义。
 */
private val HOST_PATH = Regex(
    buildString {
        append("/data/(?:user/\\d+|data|app|misc)[A-Za-z0-9._/-]*")
        append("|/storage/(?:emulated/\\d+|self|[A-Za-z0-9._-]+)[A-Za-z0-9._/-]*")
        append("|/Users/[A-Za-z0-9._-]+[A-Za-z0-9._/-]*")
        append("|[A-Za-z]:[\\\\/][A-Za-z0-9._\\\\/-]*")
    },
)

/** 把消息中的宿主绝对路径替换为 `<host>`（容器内相对路径不受影响）。 */
internal fun scrubHostPaths(message: String?): String =
    message?.let { HOST_PATH.replace(it, "<host>") } ?: ""

/** /term/{file} 白名单：终端页静态资产（core/server resources web/term/）。 */
private val TERM_ASSETS = setOf("xterm.min.js", "addon-fit.min.js", "xterm.min.css")

/** 落地页里解锁状态的占位符（服务端渲染时替换为 JSON）。 */
internal const val GATE_PLACEHOLDER = "__GATE_JSON__"
