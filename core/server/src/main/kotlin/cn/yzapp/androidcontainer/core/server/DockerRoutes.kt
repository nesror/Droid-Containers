package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.ImageRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Docker Engine API 只读兼容层（M9 阶段一，m8_m9 §4.1）。
 * 无 header 鉴权（docker CLI 无法带 header），依赖独立端口 + 用户明示开启。
 * 写操作（run/stop/rm/pull/exec）为阶段二。
 */
internal fun Route.dockerApi(images: ImageRepository, containers: ContainerRepository) {

    get("/_ping") {
        call.response.dockerHeaders()
        call.respondText("OK", ContentType.Text.Plain)
    }

    get("/version") {
        call.response.dockerHeaders()
        call.respondText(
            buildJsonObject {
                put("Platform", buildJsonObject { put("Name", "Android Container Master (proot)") })
                put("Components", buildJsonArray {
                    add(buildJsonObject {
                        put("Name", "Engine")
                        put("Version", DockerJson.SERVER_VERSION)
                        put("Details", buildJsonObject {
                            put("ApiVersion", DockerJson.API_VERSION)
                            put("MinAPIVersion", DockerJson.MIN_API_VERSION)
                        })
                    })
                })
                put("Version", DockerJson.SERVER_VERSION)
                put("ApiVersion", DockerJson.API_VERSION)
                put("MinAPIVersion", DockerJson.MIN_API_VERSION)
                put("GitCommit", "none")
                put("GoVersion", "none")
                put("Os", "linux")
                put("Arch", DockerJson.arch())
                put("KernelVersion", "proot")
                put("BuildTime", "2026-01-01T00:00:00Z")
            }.toString(),
            DockerJson.JSON,
        )
    }

    get("/info") {
        call.response.dockerHeaders()
        val all = containers.observeContainers()
        val snapshot = all.first()
        val running = snapshot.count { containers.isRunning(it.id) }
        val imagesList = images.inventory.first()
        call.respondText(
            buildJsonObject {
                put("ID", "ANDC:proot")
                put("Containers", snapshot.size)
                put("ContainersRunning", running)
                put("ContainersPaused", 0)
                put("ContainersStopped", snapshot.size - running)
                put("Images", imagesList.size)
                put("Driver", "proot")
                put("Name", android.os.Build.MODEL)
                put("ServerVersion", DockerJson.SERVER_VERSION)
                put("OperatingSystem", "Android (proot)")
                put("OSType", "linux")
                put("Architecture", DockerJson.arch())
                put("NCPU", Runtime.getRuntime().availableProcessors())
                put("MemTotal", Runtime.getRuntime().maxMemory())
                put("DockerRootDir", "")
                put("Proot", true) // 自定义字段：明示 proot 语义差异（m8_m9 §4.3）
            }.toString(),
            DockerJson.JSON,
        )
    }

    get("/containers/json") {
        call.response.dockerHeaders()
        // all=0（默认）只回运行中；filters/limit 暂不实现（阶段一只读子集）
        val showAll = call.request.queryParameters["all"] == "1" ||
            call.request.queryParameters["all"] == "true"
        val snapshot = containers.observeContainers().first()
        val list = snapshot
            .filter { showAll || containers.isRunning(it.id) }
            .map { c -> DockerJson.containerSummary(c, containers.isRunning(c.id)) }
        call.respondText(
            buildJsonArray { list.forEach { add(it) } }.toString(),
            DockerJson.JSON,
        )
    }

    get("/containers/{id}/json") {
        call.response.dockerHeaders()
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@get call.respondDockerError(
                HttpStatusCode.NotFound,
                "No such container: ${call.parameters["id"]}",
            )
        call.respondText(
            DockerJson.containerInspect(
                entity,
                containers.isRunning(entity.id),
                containers.pidOf(entity.id),
            ).toString(),
            DockerJson.JSON,
        )
    }

    get("/containers/{id}/top") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@get call.respondDockerError(
                HttpStatusCode.NotFound,
                "No such container: ${call.parameters["id"]}",
            )
        val pid = containers.pidOf(entity.id)
            ?: return@get call.respondDockerError(
                HttpStatusCode.Conflict,
                "Container ${entity.name} is not running",
            )
        val command = cn.yzapp.androidcontainer.core.data.ContainerPayloads.commandOf(entity)
            .ifEmpty { listOf("/bin/sh") }.joinToString(" ")
        call.respondText(
            buildJsonObject {
                put("Titles", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("PID"))
                    add(kotlinx.serialization.json.JsonPrimitive("USER"))
                    add(kotlinx.serialization.json.JsonPrimitive("TIME"))
                    add(kotlinx.serialization.json.JsonPrimitive("COMMAND"))
                })
                put("Processes", buildJsonArray {
                    add(buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(pid.toString()))
                        add(kotlinx.serialization.json.JsonPrimitive("root"))
                        add(kotlinx.serialization.json.JsonPrimitive("00:00:00"))
                        add(kotlinx.serialization.json.JsonPrimitive(command))
                    })
                })
            }.toString(),
            DockerJson.JSON,
        )
    }

    get("/containers/{id}/logs") {
        val entity = containers.resolveForDocker(call.parameters["id"] ?: "")
            ?: return@get call.respondDockerError(
                HttpStatusCode.NotFound,
                "No such container: ${call.parameters["id"]}",
            )
        val follow = call.request.queryParameters["follow"] == "1"
        val internalId = entity.id
        call.respondBytesWriter(contentType = ContentType.parse("application/vnd.docker.multiplexed-stream")) {
            if (follow) {
                followLogs(internalId, containers)
            } else {
                // 非跟随：整读一次（每次启动重写语义）
                val lines = containers.readLog(internalId)
                if (lines.isNotEmpty()) {
                    writeStreamFrame(lines.joinToString("\n", postfix = "\n").toByteArray())
                }
            }
        }
    }

    get("/images/json") {
        call.response.dockerHeaders()
        val snapshot = images.inventory.first()
        call.respondText(
            buildJsonArray { snapshot.forEach { add(DockerJson.imageSummary(it)) } }.toString(),
            DockerJson.JSON,
        )
    }

    get("/events") {
        call.response.dockerHeaders()
        // 容器/镜像事件流：JSON 行，客户端断开即取消
        val containerEvents = containers.events.map { event ->
            val id = event.containerId
            val dockerId = containers.resolveContainerRef(id)?.dockerId ?: id
            DockerJson.event(
                type = "container",
                action = event.type,
                actorId = dockerId,
                attributes = mapOf("name" to event.name, "image" to event.imageRef),
                timeMs = event.timestampMs,
            )
        }
        val imageEvents = images.events.map { event ->
            DockerJson.event(
                type = "image",
                action = event.type,
                actorId = DockerJson.imageId(event.ref),
                attributes = mapOf("name" to event.ref),
                timeMs = event.timestampMs,
            )
        }
        call.respondBytesWriter(contentType = DockerJson.JSON) {
            merge(containerEvents, imageEvents).collect { event ->
                writeFully((event.toString() + "\n").toByteArray())
                flush()
            }
        }
    }

    get("/system/df") {
        call.response.dockerHeaders()
        val imagesSnapshot = images.inventory.first()
        val containersSnapshot = containers.observeContainers().first()
        call.respondText(
            buildJsonObject {
                put("LayersSize", imagesSnapshot.sumOf { it.sizeBytes })
                put("Images", buildJsonArray {
                    imagesSnapshot.forEach {
                        add(
                            DockerJson.imageDf(
                                it,
                                containersSnapshot.count { c -> c.imageRef == it.ref },
                            ),
                        )
                    }
                })
                put("Containers", buildJsonArray {
                    containersSnapshot.forEach {
                        add(DockerJson.containerSummary(it, containers.isRunning(it.id)))
                    }
                })
                put("Volumes", buildJsonArray { })
                put("BuildCache", buildJsonArray { })
            }.toString(),
            DockerJson.JSON,
        )
    }
}

/**
 * 日志跟随：多路复用帧格式（8 字节头 + payload，stdout/stderr 合流走 1 号流），
 * 500ms 轮询日志文件增量；容器停止且无新增数据后收尾（与 docker logs -f 行为一致）。
 */
private suspend fun ByteWriteChannel.followLogs(
    containerId: String,
    containers: ContainerRepository,
) {
    var offset = 0L
    var idleAfterExit = 0
    while (true) {
        val chunk = containers.readLogFrom(containerId, offset)
        if (chunk.lines.isNotEmpty()) {
            writeStreamFrame(chunk.lines.joinToString("\n", postfix = "\n").toByteArray())
            offset = chunk.nextOffset
            idleAfterExit = 0
        }
        if (!containers.isRunning(containerId)) {
            if (++idleAfterExit >= 2) return // 停止后连续两轮无数据 → 结束流
        }
        delay(500)
    }
}

/** 写一帧 Docker 多路复用流：[STREAM_TYPE, 0, 0, 0, SIZE...] + payload。 */
private suspend fun ByteWriteChannel.writeStreamFrame(payload: ByteArray) {
    val header = ByteArray(8)
    header[0] = 1 // stdout
    val len = payload.size
    header[4] = (len ushr 24).toByte()
    header[5] = (len ushr 16).toByte()
    header[6] = (len ushr 8).toByte()
    header[7] = len.toByte()
    writeFully(header, 0, 8)
    writeFully(payload, 0, len)
    flush()
}
