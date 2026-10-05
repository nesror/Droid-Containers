package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.ImageRepository
import cn.yzapp.androidcontainer.core.data.RemoteAuditLog
import cn.yzapp.androidcontainer.core.model.EngineException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * exec 会话注册表（内存态）：POST /containers/{id}/exec 生成 execId，
 * POST /exec/{id}/start 按 execId 找回容器与命令（Docker exec 协议两段式，m8_m9 §4.4-4）。
 */
internal object ExecRegistry {
    data class ExecConfig(val containerId: String, val command: String, val tty: Boolean)

    /** 票据有效期（审查 P1-9）：无 TTL 的注册表可被反复 create 无限累积。 */
    private const val TTL_MS = 60_000L

    private data class Entry(val config: ExecConfig, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun create(config: ExecConfig): String {
        val id = java.security.SecureRandom().let { rng ->
            ByteArray(16).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        }
        entries[id] = Entry(config, System.currentTimeMillis() + TTL_MS)
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAt < now }
        return id
    }

    /** 单次消费语义之外的普通查找：命中且未过期返回；过期即失效并清除。 */
    fun get(id: String): ExecConfig? {
        val entry = entries[id] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            entries.remove(id)
            return null
        }
        return entry.config
    }
}

/**
 * Docker Engine API 写操作（M9 阶段二，m8_m9 §4.4）：
 * containers create/start/stop/restart/kill/rm、images create(pull)/delete、exec、rename。
 * proot 语义差异：kill 等价 stop（SIGTERM 先子后父）；exec 输出取自运行日志（无独立进程流）。
 */
internal fun Route.dockerWriteRoutes(
    images: ImageRepository,
    containers: ContainerRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {

    post("/containers/create") {
        call.response.dockerHeaders()
        val body = parseJsonObject(call.receiveTextLimited())
        val imageRef = body?.string("Image")?.takeIf { it.isNotBlank() }
            ?: return@post call.respondDockerError(HttpStatusCode.BadRequest, "field 'Image' required")
        val name = body.string("Name")?.takeIf { it.isNotBlank() }
            ?: "andc-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val cmd = (body?.get("Cmd") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        val entity = try {
            containers.create(name, imageRef, cmd)
        } catch (e: EngineException) {
            return@post call.respondDockerError(HttpStatusCode.BadRequest, e.message ?: "create failed")
        }
        RemoteAuditLog.record("docker", "create", "${entity.name} (${entity.imageRef})")
        call.respondText(
            buildJsonObject {
                put("Id", entity.dockerId ?: entity.id)
                put("Warnings", kotlinx.serialization.json.buildJsonArray { })
            }.toString(),
            DockerJson.JSON,
            HttpStatusCode.Created,
        )
    }

    post("/containers/{id}/start") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        containers.start(entity.id)
        RemoteAuditLog.record("docker", "start", entity.name)
        call.respondText("", ContentType.Text.Plain)
    }

    post("/containers/{id}/stop") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        containers.stop(entity.id)
        RemoteAuditLog.record("docker", "stop", entity.name)
        call.respondText("", ContentType.Text.Plain)
    }

    // proot 语义差异（m8_m9 §4.3）：无独立 kill 信号链，kill 走先子后父 stop
    post("/containers/{id}/kill") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        containers.stop(entity.id)
        RemoteAuditLog.record("docker", "kill", entity.name)
        call.respondText("", ContentType.Text.Plain)
    }

    post("/containers/{id}/restart") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        runCatching { containers.stop(entity.id) }
        containers.start(entity.id)
        RemoteAuditLog.record("docker", "restart", entity.name)
        call.respondText("", ContentType.Text.Plain)
    }

    delete("/containers/{id}") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@delete call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        containers.remove(entity.id)
        RemoteAuditLog.record("docker", "rm", entity.name)
        call.respondText(buildJsonObject { put("Id", entity.dockerId ?: entity.id) }.toString(), DockerJson.JSON)
    }

    post("/containers/{id}/rename") {
        val newName = call.request.queryParameters["name"]
            ?: return@post call.respondDockerError(HttpStatusCode.BadRequest, "query 'name' required")
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        try {
            containers.rename(entity.id, newName)
        } catch (e: EngineException) {
            return@post call.respondDockerError(HttpStatusCode.Conflict, e.message ?: "rename failed")
        }
        RemoteAuditLog.record("docker", "rename", "${entity.name} -> $newName")
        call.respondText("", ContentType.Text.Plain)
    }

    // docker pull：application/json 行流进度（m8_m9 §4.4-2）
    post("/images/create") {
        call.response.dockerHeaders()
        val fromImage = call.request.queryParameters["fromImage"]
            ?: return@post call.respondDockerError(HttpStatusCode.BadRequest, "query 'fromImage' required")
        val tag = call.request.queryParameters["tag"]?.takeIf { it.isNotBlank() } ?: "latest"
        val ref = if (fromImage.contains(":") && tag == "latest") fromImage else "$fromImage:$tag"
        RemoteAuditLog.record("docker", "pull", ref)
        call.respondBytesWriter(contentType = DockerJson.JSON) {
            val pullJob = scope.launch { runCatching { images.pull(ref) } }
            var lastStage: String? = null
            // 总时长上限（审查 P1-8）：拉取悬挂时不得永久占用连接与协程
            val deadline = System.currentTimeMillis() + PULL_STREAM_TIMEOUT_MS
            while (pullJob.isActive && System.currentTimeMillis() < deadline) {
                val progress = images.pullStates.value[ref]
                val stage = progress?.stage?.name ?: "PULLING"
                if (stage != lastStage) {
                    lastStage = stage
                    val line = buildJsonObject {
                        put("status", progress?.message ?: "Pulling $ref")
                        put("id", ref)
                        progress?.let {
                            put("progressDetail", buildJsonObject {
                                put("current", it.downloadedBytes)
                                put("total", it.totalBytes)
                            })
                        }
                    }.toString()
                    writeFully((line + "\n").toByteArray())
                    flush()
                }
                delay(500)
            }
            val timedOut = pullJob.isActive
            if (timedOut) pullJob.cancel()
            val failed = timedOut ||
                images.pullStates.value[ref]?.stage == cn.yzapp.androidcontainer.core.model.PullStage.FAILED
            val finalLine = if (failed) {
                buildJsonObject {
                    put("error", if (timedOut) "pull timed out: $ref" else "pull failed: $ref")
                }.toString()
            } else {
                buildJsonObject {
                    put("status", "Status: Downloaded newer image for $ref")
                    put("id", ref)
                }.toString()
            }
            writeFully((finalLine + "\n").toByteArray())
            flush()
        }
    }

    delete("/images/{name}") {
        val ref = call.parameters["name"] ?: ""
        val ok = runCatching { images.remove(ref) }.isSuccess
        if (!ok) {
            return@delete call.respondDockerError(HttpStatusCode.NotFound, "No such image: $ref")
        }
        RemoteAuditLog.record("docker", "rmi", ref)
        call.respondText(
            buildJsonArray {
                add(buildJsonObject { put("Untagged", ref); put("Deleted", ref) })
            }.toString(),
            DockerJson.JSON,
        )
    }

    // ---- exec（非 tty，m8_m9 §4.4-4）----

    post("/containers/{id}/exec") {
        call.response.dockerHeaders()
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such container: ${call.parameters["id"]}")
        if (!containers.isRunning(entity.id)) {
            return@post call.respondDockerError(HttpStatusCode.Conflict, "Container ${entity.name} is not running")
        }
        val body = parseJsonObject(call.receiveTextLimited())
        val cmd = (body?.get("Cmd") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        if (cmd.isEmpty()) {
            return@post call.respondDockerError(HttpStatusCode.BadRequest, "field 'Cmd' required")
        }
        val tty = (body?.get("Tty") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val execId = ExecRegistry.create(ExecRegistry.ExecConfig(entity.id, cmd.joinToString(" "), tty))
        call.respondText(
            buildJsonObject { put("Id", execId) }.toString(),
            DockerJson.JSON,
            HttpStatusCode.Created,
        )
    }

    post("/exec/{id}/start") {
        val execId = call.parameters["id"] ?: ""
        val config = ExecRegistry.get(execId)
            ?: return@post call.respondDockerError(HttpStatusCode.NotFound, "No such exec instance: $execId")
        if (config.tty) {
            return@post call.respondDockerError(
                HttpStatusCode.BadRequest,
                "tty mode not supported on proot; run docker exec without -t",
            )
        }
        val body = parseJsonObject(call.receiveTextLimited())
        val detach = (body?.get("Detach") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        if (detach) {
            containers.exec(config.containerId, config.command)
            RemoteAuditLog.record("docker", "exec", "${config.containerId}: ${config.command} (detach)")
            call.respondText("", ContentType.Text.Plain)
            return@post
        }
        // 非 tty raw-stream：exec 输出汇入运行日志 → 记录偏移后增量推帧，
        // 空闲 1.5s（或容器停止后一轮无新增）视为命令结束（无独立进程退出信号）
        val startOffset = containers.readLogFrom(config.containerId, Long.MAX_VALUE).nextOffset
        containers.exec(config.containerId, config.command)
        RemoteAuditLog.record("docker", "exec", "${config.containerId}: ${config.command}")
        call.respondBytesWriter(contentType = ContentType.parse("application/vnd.docker.raw-stream")) {
            var offset = startOffset
            var idleRounds = 0
            // 总时长上限（审查 P1-8）：follow 流（如 tail -f）必须有界，
            // 否则连接与协程会被永久占用
            val deadline = System.currentTimeMillis() + EXEC_STREAM_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val chunk = containers.readLogFrom(config.containerId, offset)
                if (chunk.lines.isNotEmpty()) {
                    writeStreamFrame(chunk.lines.joinToString("\n", postfix = "\n").toByteArray())
                    offset = chunk.nextOffset
                    idleRounds = 0
                } else {
                    idleRounds++
                    val stopped = !containers.isRunning(config.containerId)
                    if ((idleRounds >= 3 && !stopped) || (stopped && idleRounds >= 2)) return@respondBytesWriter
                }
                delay(500)
            }
        }
    }

    post("/exec/{id}/resize") {
        // proot 无 pty，resize 无意义；200 空响应保持 CLI 兼容
        call.respondText("", ContentType.Text.Plain)
    }

    get("/exec/{id}/json") {
        val config = ExecRegistry.get(call.parameters["id"] ?: "")
            ?: return@get call.respondDockerError(HttpStatusCode.NotFound, "No such exec instance: ${call.parameters["id"]}")
        call.respondText(
            buildJsonObject {
                put("ID", call.parameters["id"] ?: "")
                put("Running", false)
                put("ProcessConfig", buildJsonObject {
                    put("tty", config.tty)
                    put("entrypoint", "/bin/sh")
                    put("arguments", buildJsonArray { add(JsonPrimitive("-c")); add(JsonPrimitive(config.command)) })
                })
            }.toString(),
            DockerJson.JSON,
        )
    }
}

private suspend fun ByteWriteChannel.writeStreamFrame(payload: ByteArray) {
    val header = ByteArray(8)
    header[0] = 1 // stdout（stderr 也并流，m8_m9 §4.4-4）
    val len = payload.size
    header[4] = (len ushr 24).toByte()
    header[5] = (len ushr 16).toByte()
    header[6] = (len ushr 8).toByte()
    header[7] = len.toByte()
    writeFully(header, 0, 8)
    writeFully(payload, 0, len)
    flush()
}

/** pull 进度流总时长上限（审查 P1-8）：悬挂的拉取不得永久占用连接与协程。 */
private const val PULL_STREAM_TIMEOUT_MS = 30 * 60 * 1000L

/** exec follow 流总时长上限（审查 P1-8）：`tail -f` 一类命令同样必须有界。 */
private const val EXEC_STREAM_TIMEOUT_MS = 30 * 60 * 1000L

private fun parseJsonObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
