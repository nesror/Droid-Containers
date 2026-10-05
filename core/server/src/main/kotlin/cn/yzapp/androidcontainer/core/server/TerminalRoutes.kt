package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.RemoteAuditLog
import cn.yzapp.androidcontainer.core.model.EngineException
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.io.Buffer
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * 容器终端 WebSocket 通道（浏览器端 xterm.js 直连）：
 * `GET /api/v1/containers/{id}/terminal?token=&cols=&rows=` → 101 Upgrade。
 *
 * 帧协议：
 * - 客户端 → 服务端：Binary = 键盘输入字节；Text = 控制指令 JSON（`{"type":"resize","cols":..,"rows":..}`）；
 * - 服务端 → 客户端：Binary = pty 输出；Text = `{"type":"exit","code":..}`（会话结束，随后服务端关闭连接）。
 *
 * 鉴权：浏览器 WebSocket API 不能带自定义 header，鉴权中间件对本路径额外接受 `?token=`；
 * 模板包门禁沿用 `/api/v1` 前缀拦截（未解锁时 upgrade 前返回 402）。
 */
internal fun Route.terminalRoutes(containers: ContainerRepository) {

    webSocket("/containers/{id}/terminal") {
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing container id"))
            return@webSocket
        }
        val cols = call.request.queryParameters["cols"]?.toIntOrNull() ?: 80
        val rows = call.request.queryParameters["rows"]?.toIntOrNull() ?: 24

        // 三级解析与 REST 一致（内部 id / dockerId 前缀 / 名称）
        val entity = containers.resolveContainerRef(id)
        if (entity == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such container: $id"))
            return@webSocket
        }

        val session = try {
            containers.openTerminal(entity.id, cols, rows)
        } catch (e: EngineException) {
            RemoteAuditLog.record("web", "terminal", entity.name, false)
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "[${e.code}] ${e.message}"))
            return@webSocket
        }
        RemoteAuditLog.record("web", "terminal", entity.name)

        // 断线重连回放：本会话新建时无历史，发空快照固定「先二进制帧」的时序约定
        send(binaryFrame(session.drainReplay()))

        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val writerJob = launch {
            merge(
                session.output.map { binaryFrame(it) },
                session.exit.map { code ->
                    exitCode.set(code)
                    Frame.Text(
                        buildJsonObject {
                            put("type", "exit")
                            put("code", code)
                        }.toString(),
                    )
                },
            ).collect { frame -> send(frame) }
        }

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> session.write(frame.data)
                    is Frame.Text -> {
                        val payload = runCatching {
                            Json.parseToJsonElement(frame.data.decodeToString()).jsonObject
                        }.getOrNull() ?: continue
                        when ((payload["type"] as? JsonPrimitive)?.content) {
                            "resize" -> session.resize(
                                (payload["cols"] as? JsonPrimitive)?.content?.toIntOrNull() ?: cols,
                                (payload["rows"] as? JsonPrimitive)?.content?.toIntOrNull() ?: rows,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        } finally {
            writerJob.cancel()
            session.close()
            if (exitCode.get() == Int.MIN_VALUE) {
                // 客户端先断开（未收到 exit 事件）：会话已随 close 杀掉，补发退出语义
                runCatching { send(Frame.Text("""{"type":"exit","code":-1}""")) }
            }
            try {
                close(CloseReason(CloseReason.Codes.NORMAL, "session closed"))
            } catch (_: Exception) {
                // 连接已断时忽略
            }
        }
    }
}

/** Ktor 3：Frame.Binary 需要显式 fin + kotlinx.io Source。 */
private fun binaryFrame(bytes: ByteArray): Frame.Binary =
    Frame.Binary(true, Buffer().apply { write(bytes) })
