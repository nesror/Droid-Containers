package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.billing.EntitlementRepository
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.common.LanAddress
import cn.yzapp.androidcontainer.core.data.ComposeRepository
import cn.yzapp.androidcontainer.core.data.ContainerPayloads
import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.ImageRepository
import cn.yzapp.androidcontainer.core.data.RemoteAuditLog
import cn.yzapp.androidcontainer.core.data.SettingsRepository
import cn.yzapp.androidcontainer.core.data.composeProjectState
import cn.yzapp.androidcontainer.core.engine.compose.TemplateCatalog
import cn.yzapp.androidcontainer.core.model.PullStage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.ktor.utils.io.writeStringUtf8

/** 请求引用不存在的资源（映射 404）。 */
internal class HttpNotFoundException(message: String) : Exception(message)

/**
 * 自定义 REST API（M8 阶段一，m8_m9 §3.1-3）：仓库层薄适配，仓库层零改动。
 * 鉴权由外层 webModule 统一处理。
 */
internal fun Route.apiRoutes(
    images: ImageRepository,
    containers: ContainerRepository,
    compose: ComposeRepository,
    entitlement: EntitlementRepository,
    settings: SettingsRepository,
    scope: CoroutineScope,
) {

    // ---- 终端票据（审查 P1-4）----

    // Bearer 换一次性短时票据：WS 升级 URL 只出示票据，长期 token 不进 URL
    post("/terminal-ticket") {
        call.respondJson(buildJsonObject {
            put("ticket", TerminalTicketStore.issue())
        }.toString())
    }

    // ---- 镜像 ----

    get("/images") {
        val list = images.inventory.first()
        call.respondJson(buildJsonArray {
            list.forEach { image ->
                add(buildJsonObject {
                    put("ref", image.ref)
                    put("repo", image.repo)
                    put("tag", image.tag)
                    put("sizeBytes", image.sizeBytes)
                    put("createdAt", image.createdAt)
                })
            }
        }.toString())
    }

    post("/images/pull") {
        val ref = call.bodyField("ref")
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "field 'ref' required")
        scope.launch {
            val ok = runCatching { images.pull(ref) }.isSuccess
            RemoteAuditLog.record("web", "pull", ref, ok)
        }
        RemoteAuditLog.record("web", "pull", ref)
        call.respondJson(simpleJson("status" to "pulling", "ref" to ref), HttpStatusCode.Accepted)
    }

    get("/pull-states") {
        call.respondJson(buildJsonObject {
            images.pullStates.value.forEach { (ref, progress) ->
                put(ref, buildJsonObject {
                    put("stage", progress.stage.name)
                    put("currentLayer", progress.currentLayer)
                    put("totalLayers", progress.totalLayers)
                    put("completedLayers", progress.completedLayers)
                    put("downloadedBytes", progress.downloadedBytes)
                    put("totalBytes", progress.totalBytes)
                    put("overallDownloadedBytes", progress.overallDownloadedBytes)
                    put("overallTotalBytes", progress.overallTotalBytes)
                    put("message", progress.message)
                    // 正在使用的镜像源（排查「卡住/慢/失败」时判断走的是哪个源）
                    put("mirrorHost", progress.mirrorHost)
                    put("done", progress.stage == PullStage.READY || progress.stage == PullStage.FAILED)
                })
            }
        }.toString())
    }

    delete("/images") {
        val ref = call.request.queryParameters["ref"]
            ?: return@delete call.respondError(HttpStatusCode.BadRequest, "query 'ref' required")
        if (call.request.queryParameters["confirm"] != "true") {
            // 删除类操作 API 层二次确认（m8_m9 §3.3）
            return@delete call.respondError(HttpStatusCode.BadRequest, "pass confirm=true to delete")
        }
        val ok = runCatching { images.remove(ref) }.isSuccess
        RemoteAuditLog.record("web", "rmi", ref, ok)
        if (ok) call.respondJson(simpleJson("deleted" to ref)) else call.respondError(HttpStatusCode.InternalServerError, "delete failed")
    }

    // ---- 容器 ----

    get("/containers") {
        val snapshot = containers.observeContainers().first()
        val runtime = containers.runtimeStates.value
        call.respondJson(buildJsonArray {
            snapshot.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("image", c.imageRef)
                    put("status", if (runtime[c.id] == cn.yzapp.androidcontainer.core.data.ContainerRuntime.RUNNING || containers.isRunning(c.id)) "RUNNING" else c.status.name)
                    put("autoStart", c.autoStart)
                    put("createdAt", c.createdAt)
                    c.projectId?.let { put("projectId", it) }
                    c.serviceName?.let { put("serviceName", it) }
                    // 服务直达：容器编辑器里声明过的 HTTP 端口（容器端口 == 局域网可访问端口）
                    put(
                        "ports",
                        buildJsonArray { ContainerPayloads.httpPortsOf(c).forEach { add(JsonPrimitive(it)) } },
                    )
                })
            }
        }.toString())
    }

    post("/containers") {
        val body = call.receiveJsonObject()
        val name = body?.string("name")
        val image = body?.string("image")
        if (name.isNullOrBlank() || image.isNullOrBlank()) {
            return@post call.respondError(HttpStatusCode.BadRequest, "fields 'name' and 'image' required")
        }
        val entity = containers.create(name, image, emptyList(), body.boolean("autoStart"))
        RemoteAuditLog.record("web", "create", "${entity.name} (${entity.imageRef})")
        call.respondJson(buildJsonObject {
            put("id", entity.id)
            put("name", entity.name)
            put("image", entity.imageRef)
        }.toString(), HttpStatusCode.Created)
    }

    post("/containers/{id}/start") {
        val id = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing id")
        containers.start(id)
        RemoteAuditLog.record("web", "start", id)
        call.respondJson(simpleJson("started" to id))
    }

    post("/containers/{id}/stop") {
        val id = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing id")
        containers.stop(id)
        RemoteAuditLog.record("web", "stop", id)
        call.respondJson(simpleJson("stopped" to id))
    }

    post("/containers/{id}/exec") {
        val id = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing id")
        val command = call.bodyField("command")
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "field 'command' required")
        containers.exec(id, command)
        RemoteAuditLog.record("web", "exec", "$id: $command")
        call.respondJson(simpleJson("exec" to "queued", "note" to "output appears in logs"), HttpStatusCode.Accepted)
    }

    get("/containers/{id}/logs") {
        val id = call.parameters["id"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing id")
        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
        if (call.request.queryParameters["follow"] == "1") {
            call.respondText(
                "text/event-stream placeholder; use offset polling",
                io.ktor.http.ContentType.Text.Plain,
                HttpStatusCode.NotImplemented,
            )
            return@get
        }
        val chunk = containers.readLogFrom(id, offset)
        call.respondJson(buildJsonObject {
            put("offset", chunk.nextOffset)
            put("lines", buildJsonArray { chunk.lines.forEach { add(JsonPrimitive(it)) } })
        }.toString())
    }

    delete("/containers/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respondError(HttpStatusCode.BadRequest, "missing id")
        if (call.request.queryParameters["confirm"] != "true") {
            return@delete call.respondError(HttpStatusCode.BadRequest, "pass confirm=true to delete")
        }
        containers.remove(id)
        RemoteAuditLog.record("web", "rm", id)
        call.respondJson(simpleJson("deleted" to id))
    }

    // ---- 编排 ----

    get("/compose/projects") {
        val projects = compose.observeProjects().first()
        val runtime = containers.runtimeStates.value
        val allContainers = containers.observeContainers().first()
        call.respondJson(buildJsonArray {
            projects.forEach { project ->
                val projectContainers = allContainers.filter { it.projectId == project.id }
                val plan = ServiceLinks.planOfProject(project.yamlContent)
                add(buildJsonObject {
                    put("id", project.id)
                    put("name", project.name)
                    put("yaml", project.yamlContent)
                    put("serviceCount", plan.serviceCount)
                    put("state", composeProjectState(plan.serviceCount, projectContainers, runtime).name)
                    // 服务直达：正文声明的容器端口 + 容器上手工声明过的 HTTP 端口（后者是「明确是 Web 服务」的信号）
                    put("ports", buildJsonArray { plan.ports.forEach { add(JsonPrimitive(it)) } })
                    put(
                        "httpPorts",
                        buildJsonArray {
                            ServiceLinks.httpPortsOfContainers(projectContainers).forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    put("parseError", plan.parseError)
                })
            }
        }.toString())
    }

    post("/compose") {
        val body = call.receiveJsonObject()
        val name = body?.string("name")
        val yaml = body?.string("yaml")
        if (name.isNullOrBlank() || yaml.isNullOrBlank()) {
            return@post call.respondError(HttpStatusCode.BadRequest, "fields 'name' and 'yaml' required")
        }
        val entity = compose.createProject(name, yaml)
        RemoteAuditLog.record("web", "compose.create", entity.name)
        call.respondJson(simpleJson("id" to entity.id, "name" to entity.name), HttpStatusCode.Created)
    }

    put("/compose/{id}") {
        val id = call.parameters["id"] ?: return@put call.respondError(HttpStatusCode.BadRequest, "missing id")
        val body = call.receiveJsonObject()
        val name = body?.string("name")
        val yaml = body?.string("yaml")
        if (name.isNullOrBlank() || yaml.isNullOrBlank()) {
            return@put call.respondError(HttpStatusCode.BadRequest, "fields 'name' and 'yaml' required")
        }
        compose.updateProject(id, name, yaml)
        RemoteAuditLog.record("web", "compose.update", name)
        call.respondJson(simpleJson("id" to id))
    }

    post("/compose/{id}/up") {
        val id = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing id")
        val report = compose.up(id)
        RemoteAuditLog.record("web", "up", report.projectName)
        call.respondJson(buildJsonObject {
            put("project", report.projectName)
            put("startedServices", buildJsonArray { report.startedServices.forEach { add(JsonPrimitive(it)) } })
            // 解析/运行时提示随 up 下发（端口冲突之外的 bind 忽略、就绪超时等，Web 控制台展示用）
            put("issues", buildJsonArray {
                report.issues.forEach { issue ->
                    add(buildJsonObject {
                        put("service", issue.service?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("key", issue.key)
                        put("kind", issue.kind.name)
                        put("message", issue.message)
                    })
                }
            })
        }.toString())
    }

    post("/compose/{id}/down") {
        val id = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing id")
        val removeContainers = call.request.queryParameters["removeContainers"] == "true"
        val stopped = compose.down(id, removeContainers)
        RemoteAuditLog.record("web", "down", "project=$id removed=$removeContainers")
        call.respondJson(buildJsonObject {
            put("stopped", buildJsonArray { stopped.forEach { add(JsonPrimitive(it)) } })
        }.toString())
    }

    delete("/compose/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respondError(HttpStatusCode.BadRequest, "missing id")
        if (call.request.queryParameters["confirm"] != "true") {
            return@delete call.respondError(HttpStatusCode.BadRequest, "pass confirm=true to delete")
        }
        compose.deleteProject(id, call.request.queryParameters["removeContainers"] == "true")
        RemoteAuditLog.record("web", "compose.delete", id)
        call.respondJson(simpleJson("deleted" to id))
    }

    // ---- 模板 / 权益 ----

    /**
     * 模板库：文案按请求语言解析（`?lang=` > `Accept-Language` > 英语）。
     *
     * 锁定的 Pro 模板**不下发 `compose` 正文**——内容级门禁的实质在 `TemplateFeed`，
     * 服务级门禁（未解锁整个 `/api/v1` 都不可用）由 `RemoteHttpServer` 拦截。
     */
    get("/templates") {
        val language = TemplateFeed.resolveLanguage(
            call.request.queryParameters["lang"],
            call.request.headers[io.ktor.http.HttpHeaders.AcceptLanguage],
        )
        val state = entitlement.state.first()
        call.respondJson(TemplateFeed.build(TemplateCatalog.builtIn(), state, language).toString())
    }

    /** 主动刷新解锁状态（控制台进入模板库时后台调一次）；刷新失败不报错，返回缓存状态并标 `stale`。 */
    post("/entitlement/refresh") {
        val refreshed = runCatching { entitlement.refresh(force = true) }.isSuccess
        val state = entitlement.state.first()
        call.respondJson(buildJsonObject {
            put("state", EntitlementGate.stateName(state))
            put("unlocked", EntitlementGate.isAllowed(state))
            put("stale", !refreshed)
        }.toString())
    }

    // ---- 设置 / 审计 ----

    get("/settings") {
        call.respondJson(buildJsonObject {
            put("device", android.os.Build.MODEL)
            put("lanAddresses", buildJsonArray { LanAddress.allIpv4().forEach { add(JsonPrimitive(it)) } })
            put("webPort", settings.webPort.first())
            put("dockerPort", settings.dockerPort.first())
            put("webEnabled", settings.webEnabled.first())
            put("dockerEnabled", settings.dockerEnabled.first())
            // 只报解锁状态，**绝不返回 apiToken**（凭据只存在于用户设备的 localStorage 与 App 内）
            put("entitlement", entitlementJson(entitlement.state.first()))
        }.toString())
    }

    get("/audit") {
        call.respondJson(buildJsonArray {
            RemoteAuditLog.recent().forEach { entry ->
                add(buildJsonObject {
                    put("time", entry.timeMs)
                    put("source", entry.source)
                    put("action", entry.action)
                    put("detail", entry.detail)
                    put("success", entry.success)
                })
            }
        }.toString())
    }

    // ---- SSE 实时通道（M8 阶段二，m8_m9 §3.2-3）----

    /** 拉取进度流：全量 pullStates 快照变化即推送（StateFlow 初始值立即下发）。 */
    get("/stream/progress") {
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            images.pullStates.collect { states ->
                val payload = buildJsonObject {
                    states.forEach { (ref, progress) -> put(ref, progressJson(progress)) }
                }
                writeStringUtf8("event: progress\ndata: ${payload}\n\n")
                flush()
            }
        }
    }

    /** 日志跟随流：500ms 轮询增量读取；容器停止且两轮无新增后发 end 事件收尾。 */
    get("/containers/{id}/logs/follow") {
        val ref = call.parameters["id"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing id")
        val entity = containers.resolveContainerRef(ref)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "no such container: $ref")
        val start = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            var offset = start
            var idleAfterExit = 0
            while (true) {
                val chunk = containers.readLogFrom(entity.id, offset)
                if (chunk.lines.isNotEmpty()) {
                    val lines = buildJsonArray { chunk.lines.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    writeStringUtf8("data: {\"offset\":${chunk.nextOffset},\"lines\":$lines}\n\n")
                    flush()
                    offset = chunk.nextOffset
                    idleAfterExit = 0
                }
                if (!containers.isRunning(entity.id)) {
                    if (++idleAfterExit >= 2) {
                        writeStringUtf8("event: end\ndata: {}\n\n")
                        flush()
                        return@respondBytesWriter
                    }
                }
                delay(500)
            }
        }
    }
}

/** 顶层错误映射：EngineException → {code,message}，未捕获异常 → 500。 */
internal suspend fun ApplicationCall.respondJson(text: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(text, io.ktor.http.ContentType.Application.Json, status)
}

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respondJson("""{"code":${status.value},"message":${JsonPrimitive(message)}}""", status)
}

private fun parseLenientJsonObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

private suspend fun ApplicationCall.receiveJsonObject(): JsonObject? =
    parseLenientJsonObject(receiveTextLimited())

private suspend fun ApplicationCall.bodyField(key: String): String? =
    receiveJsonObject()?.string(key)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

/** 权益状态 → JSON：只报状态与是否放行，控制台按语言做展示层翻译。 */
private fun entitlementJson(state: EntitlementState) = buildJsonObject {
    put("state", EntitlementGate.stateName(state))
    put("unlocked", EntitlementGate.isAllowed(state))
}

/** 拉取进度 → JSON（/pull-states 与 SSE 共用）。 */
private fun progressJson(progress: cn.yzapp.androidcontainer.core.model.PullProgress) = buildJsonObject {
    put("stage", progress.stage.name)
    put("currentLayer", progress.currentLayer)
    put("totalLayers", progress.totalLayers)
    put("completedLayers", progress.completedLayers)
    put("downloadedBytes", progress.downloadedBytes)
    put("totalBytes", progress.totalBytes)
    put("overallDownloadedBytes", progress.overallDownloadedBytes)
    put("overallTotalBytes", progress.overallTotalBytes)
    put("message", progress.message)
    put("done", progress.stage == PullStage.READY || progress.stage == PullStage.FAILED)
}


/** 简单键值响应 → JSON（经序列化器转义，审查 P1-9：手拼模板串可被引号注入）。 */
private fun simpleJson(vararg pairs: Pair<String, String>): String =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()

private fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
