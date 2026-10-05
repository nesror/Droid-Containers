package cn.yzapp.androidcontainer.core.engine.oci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 镜像默认入口（OCI image config 的 `config.Entrypoint` / `config.Cmd`，对齐 docker 语义）：
 * - 有 ENTRYPOINT → ENTRYPOINT + CMD（CMD 作为参数追加）；
 * - 仅 CMD → CMD；
 * - 两者皆缺 → 空列表（调用方继续走 shell 回落）。
 */
object ImageDefaultEntry {

    /**
     * 从拉取时落盘的 config blob（`layers/<safe>/config`）解析默认入口 argv。
     * 文件缺失 / 解析失败 / 字段异常一律返回空列表，不抛异常（回落路径必须可用）。
     */
    fun argvFromConfig(configFile: File): List<String> = runCatching {
        if (!configFile.isFile) return emptyList()
        val json = Json.parseToJsonElement(configFile.readText()).jsonObject
        val config = json["config"] as? JsonObject ?: return emptyList()
        val entrypoint = stringList(config, "Entrypoint")
        val cmd = stringList(config, "Cmd")
        when {
            entrypoint.isNotEmpty() -> entrypoint + cmd
            cmd.isNotEmpty() -> cmd
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /**
     * 镜像 config 的 `config.WorkingDir`（OCI WorkingDir，docker 语义：容器进程 cwd）。
     * 缺失 / 为空 / 非绝对路径 → null（调用方回落引擎默认）。halo 等镜像的 ENTRYPOINT
     * 用相对路径引用 jar（`-jar application.jar`），不应用 WorkingDir 会直接启动失败。
     */
    fun workingDirFromConfig(configFile: File): String? = runCatching {
        if (!configFile.isFile) return null
        val json = Json.parseToJsonElement(configFile.readText()).jsonObject
        val config = json["config"] as? JsonObject ?: return null
        val dir = config["WorkingDir"]?.jsonPrimitive?.content
        if (dir.isNullOrEmpty() || !dir.startsWith("/")) null else dir.trimEnd('/')
    }.getOrDefault(null)

    private fun stringList(obj: JsonObject, key: String): List<String> = runCatching {
        obj[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 镜像 config 的 `config.Env`（["K=V", ...]）→ 环境变量表。
     * docker 语义：镜像 ENV 作为基础，compose `environment` 与引擎注入可覆盖。
     * 文件缺失 / 解析失败 → 空表（不阻断启动）。
     */
    fun envFromConfig(configFile: File): Map<String, String> = runCatching {
        if (!configFile.isFile) return emptyMap()
        val json = Json.parseToJsonElement(configFile.readText()).jsonObject
        val config = json["config"] as? JsonObject ?: return emptyMap()
        stringList(config, "Env")
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
            }
            .toMap()
    }.getOrDefault(emptyMap())
}
