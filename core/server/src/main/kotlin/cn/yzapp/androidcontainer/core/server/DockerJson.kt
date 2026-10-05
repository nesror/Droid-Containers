package cn.yzapp.androidcontainer.core.server

import android.os.Build
import cn.yzapp.androidcontainer.core.data.ContainerPayloads
import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.data.db.ImageEntity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Docker Engine API 响应构造（m8_m9 §4.2）：
 * docker CLI / Portainer 对字段极敏感（缺字段直接崩），以下按官方 v1.43 规范逐字段填充；
 * proot 语义差异（无命名空间、端口共享宿主栈、退出码无区分）按"形似 + 如实"原则处理。
 */
internal object DockerJson {

    const val API_VERSION = "1.43"
    const val MIN_API_VERSION = "1.20"
    const val SERVER_VERSION = "24.0.0-proot"

    val JSON: ContentType = ContentType.Application.Json

    fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "amd64"
        else -> "unknown"
    }

    fun rfc3339(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

    /** 镜像 Id：真 digest 未持久化时用引用派生的确定性 hex（形似，客户端只用它做去重/引用）。 */
    fun imageId(ref: String): String = "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(ref.toByteArray()))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun runtimeState(e: ContainerEntity, isRunning: Boolean): String = when {
        isRunning -> "running"
        e.status == cn.yzapp.androidcontainer.core.model.ContainerStatus.CREATED -> "created"
        else -> "exited"
    }

    private fun statusText(e: ContainerEntity, isRunning: Boolean): String = when {
        isRunning -> "Up"
        e.status == cn.yzapp.androidcontainer.core.model.ContainerStatus.CREATED -> "Created"
        else -> "Exited (0)"
    }

    private fun commandText(e: ContainerEntity): String =
        ContainerPayloads.commandOf(e).ifEmpty { listOf("/bin/sh") }.joinToString(" ")

    /** /containers/json 条目（docker ps）。 */
    fun containerSummary(e: ContainerEntity, isRunning: Boolean): JsonObject = buildJsonObject {
        put("Id", e.dockerId ?: e.id)
        put("Names", buildJsonArray { add(JsonPrimitive("/" + e.name)) })
        put("Image", e.imageRef)
        put("ImageID", imageId(e.imageRef))
        put("Command", commandText(e))
        put("Created", e.createdAt / 1000)
        put("Ports", buildJsonArray { })
        put("Labels", buildJsonObject {
            e.projectId?.let { put("cn.yzapp.project", it) }
            e.serviceName?.let { put("cn.yzapp.service", it) }
        })
        put("State", runtimeState(e, isRunning))
        put("Status", statusText(e, isRunning))
        put("HostConfig", buildJsonObject { put("NetworkMode", "default") })
        put("NetworkSettings", buildJsonObject { put("Ports", buildJsonObject { }) })
        put("Mounts", buildJsonArray { })
    }

    /** /containers/{id}/json（docker inspect）。字段按 v1.43 规范填充，空值如实。 */
    fun containerInspect(e: ContainerEntity, isRunning: Boolean, pid: Int?): JsonObject {
        val command = ContainerPayloads.commandOf(e).ifEmpty { listOf("/bin/sh") }
        return buildJsonObject {
            put("Id", e.dockerId ?: e.id)
            put("Created", rfc3339(e.createdAt))
            put("Path", command.firstOrNull() ?: "/bin/sh")
            put("Args", buildJsonArray { command.drop(1).forEach { add(JsonPrimitive(it)) } })
            put("State", buildJsonObject {
                put("Status", runtimeState(e, isRunning))
                put("Running", isRunning)
                put("Paused", false)
                put("Restarting", false)
                put("OOMKilled", false)
                put("Dead", false)
                put("Pid", pid ?: 0)
                // proot 退出码无区分，统一 0（文档已标注）
                put("ExitCode", 0)
                put("Error", "")
                put("StartedAt", if (isRunning) rfc3339(e.createdAt) else "0001-01-01T00:00:00Z")
                put("FinishedAt", if (isRunning) "0001-01-01T00:00:00Z" else rfc3339(System.currentTimeMillis()))
            })
            put("Image", imageId(e.imageRef))
            put("Name", "/" + e.name)
            put("RestartCount", 0)
            put("Driver", "proot")
            put("Platform", "linux")
            put("MountLabel", "")
            put("ProcessLabel", "")
            put("AppArmorProfile", "")
            put("ExecIDs", buildJsonArray { })
            put("HostConfig", buildJsonObject {
                put("Binds", buildJsonArray { })
                put("NetworkMode", "default")
                put("PortBindings", buildJsonObject { })
                put("RestartPolicy", buildJsonObject { put("Name", "no"); put("MaximumRetryCount", 0) })
                put("AutoRemove", false)
                put("Privileged", true) // proot 无命名空间隔离，语义上接近 privileged
                put("PublishAllPorts", false)
            })
            put("GraphDriver", buildJsonObject { put("Name", "proot"); put("Data", buildJsonObject { }) })
            put("Mounts", buildJsonArray { })
            put("Config", buildJsonObject {
                put("Hostname", e.dockerId?.take(12) ?: e.id.take(12))
                put("Domainname", "")
                put("User", "")
                put("AttachStdin", true)
                put("AttachStdout", true)
                put("AttachStderr", true)
                put("Tty", false)
                put("OpenStdin", true)
                put("StdinOnce", false)
                put("Env", buildJsonArray {
                    ContainerPayloads.environmentOf(e).forEach { (k, v) -> add(JsonPrimitive("$k=$v")) }
                })
                put("Cmd", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
                put("Image", e.imageRef)
                put("Volumes", buildJsonObject { })
                put("WorkingDir", "/")
                put("Entrypoint", buildJsonArray { })
                put("Labels", buildJsonObject { })
            })
            put("NetworkSettings", buildJsonObject {
                put("Bridge", "")
                put("Sandbox", "")
                put("HairpinMode", false)
                put("LinkLocalIPv6Address", "")
                put("LinkLocalIPv6PrefixLen", 0)
                put("Ports", buildJsonObject { }) // 共享宿主网络栈：容器端口即宿主端口，无映射
                put("SandboxKey", "")
                put("SecondaryIPAddresses", buildJsonArray { })
                put("SecondaryIPv6Addresses", buildJsonArray { })
                put("Networks", buildJsonObject { })
            })
        }
    }

    /** /images/json 条目（docker images）。 */
    fun imageSummary(e: ImageEntity): JsonObject = buildJsonObject {
        put("Id", imageId(e.ref))
        put("ParentId", "")
        put("RepoTags", buildJsonArray { add(JsonPrimitive(e.ref)) })
        put("RepoDigests", buildJsonArray { })
        put("Created", e.createdAt)
        put("Size", e.sizeBytes)
        put("VirtualSize", e.sizeBytes)
        put("SharedSize", 0)
        put("Labels", buildJsonObject { })
        put("Containers", -1)
    }

    /** /system/df 的 Images 空间汇总（docker system df）。 */
    fun imageDf(e: ImageEntity, containersUsing: Int): JsonObject = buildJsonObject {
        put("Id", imageId(e.ref))
        put("ParentId", "")
        put("RepoTags", buildJsonArray { add(JsonPrimitive(e.ref)) })
        put("RepoDigests", buildJsonArray { })
        put("Created", e.createdAt)
        put("Size", e.sizeBytes)
        put("SharedSize", 0)
        put("VirtualSize", e.sizeBytes)
        put("Labels", buildJsonObject { })
        put("Containers", containersUsing)
    }

    /** Docker 事件（/events 流，m8_m9 §4.2-6）。 */
    fun event(type: String, action: String, actorId: String, attributes: Map<String, String>, timeMs: Long): JsonObject =
        buildJsonObject {
            put("Type", type)
            put("Action", action)
            put("Actor", buildJsonObject {
                put("ID", actorId)
                put("Attributes", buildJsonObject { attributes.forEach { (k, v) -> put(k, v) } })
            })
            put("scope", "local")
            put("time", timeMs / 1000)
            put("timeNano", timeMs * 1_000_000)
        }
}

/** Docker 风格 404：{"message": "..."}（CLI 依赖该格式报错）。 */
internal suspend fun ApplicationCall.respondDockerError(status: HttpStatusCode, message: String) {
    respondText("""{"message":${kotlinx.serialization.json.JsonPrimitive(message)}}""", DockerJson.JSON, status)
}

/** 统一加 Api-Version / OSType 头（客户端版本协商探测用）。 */
internal fun io.ktor.server.response.ApplicationResponse.dockerHeaders() {
    header("Api-Version", DockerJson.API_VERSION)
    header("OSType", "linux")
    header("Docker-Experimental", "false")
}
