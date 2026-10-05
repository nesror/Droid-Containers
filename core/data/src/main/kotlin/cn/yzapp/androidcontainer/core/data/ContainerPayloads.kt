package cn.yzapp.androidcontainer.core.data

import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 容器 command / environment 的库内 JSON 编解码（方案 §4 容器表 cmd/env）。
 *
 * 解码一律容错：库里存的是我们自己写的历史数据，格式异常时退化为空，
 * 由容器启动路径回落到镜像默认入口，避免一条脏数据把容器页拖挂。
 */
object ContainerPayloads {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val listSerializer = ListSerializer(String.serializer())
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val intListSerializer = ListSerializer(Int.serializer())

    fun encodeList(values: List<String>): String = json.encodeToString(listSerializer, values)

    fun decodeList(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            .orEmpty()

    fun encodeIntList(values: List<Int>): String = json.encodeToString(intListSerializer, values)

    fun decodeIntList(raw: String?): List<Int> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(intListSerializer, it) }.getOrNull() }
            .orEmpty()

    fun encodeMap(values: Map<String, String>): String = json.encodeToString(mapSerializer, values)

    fun decodeMap(raw: String?): Map<String, String> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }
            .orEmpty()

    /** 容器启动命令（空 → 调用方回落到镜像默认入口）。 */
    fun commandOf(container: ContainerEntity): List<String> = decodeList(container.cmdJson)

    /** 容器环境变量。 */
    fun environmentOf(container: ContainerEntity): Map<String, String> = decodeMap(container.envJson)

    /** 容器对外 HTTP 端口（proot 进程直接监听本机端口，浏览器 127.0.0.1 直达）。 */
    fun httpPortsOf(container: ContainerEntity): List<Int> = decodeIntList(container.portsJson)
}
