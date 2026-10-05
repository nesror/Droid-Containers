package cn.yzapp.androidcontainer.core.engine.compose

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar

/**
 * docker compose 解析器（方案 §3.1 阶段一第 2 项）。
 *
 * 设计要点：
 * - kaml 只用来拿**语法树**（[Yaml.parseToYamlNode]），语义解析全部手写，从而做到
 *   ①不支持的键逐个显式标记（不静默丢弃）、②错误能定位到具体行号；
 * - 致命错误（语法错、缺 image、依赖引用不存在、依赖成环）抛 [ComposeParseException]（带行号）；
 * - 非致命差异（未实现键、ports/volumes 语义差异、/init 入口）收进 [ComposeSpec.issues]，
 *   UI 展示但不阻塞 up。
 *
 * 纯 Kotlin，无 Android 依赖，可直接单测。
 */
object ComposeParser {

    /** 支持的 service 键；其余一律进 issues（显式标记）。 */
    private val SERVICE_KEYS = setOf(
        "image",
        "command",
        "entrypoint",
        "environment",
        "ports",
        "volumes",
        "depends_on",
        "restart",
    )

    /** 支持的顶层键。 */
    private val ROOT_KEYS = setOf("services", "version", "volumes")

    fun parse(yaml: String): ComposeSpec {
        if (yaml.isBlank()) {
            throw ComposeParseException("compose file is empty")
        }
        val root = parseNode(yaml)
        val rootMap = root as? YamlMap
            ?: throw ComposeParseException("compose file root must be a key-value mapping", root.line(), root.column())

        val issues = mutableListOf<ComposeIssue>()
        rootMap.entries.keys.forEach { key ->
            if (key.content !in ROOT_KEYS) {
                issues += ComposeIssue(
                    service = null,
                    key = key.content,
                    kind = ComposeIssueKind.UNSUPPORTED_KEY,
                    message = "top-level key \"${key.content}\" is not supported and was ignored",
                    line = key.line(),
                )
            }
        }

        val servicesNode = rootMap.node("services")
            ?: throw ComposeParseException("missing services section", root.line(), root.column())
        val servicesMap = when (servicesNode) {
            is YamlNull -> throw ComposeParseException("services section is empty", servicesNode.line(), servicesNode.column())
            is YamlMap -> servicesNode
            else -> throw ComposeParseException("services section must be a key-value mapping", servicesNode.line(), servicesNode.column())
        }
        if (servicesMap.entries.isEmpty()) {
            throw ComposeParseException("services section is empty", servicesMap.line(), servicesMap.column())
        }

        val services = servicesMap.entries.map { (key, value) ->
            parseService(key.content, value, issues, key.line())
        }

        val declaredVolumes = parseDeclaredVolumes(rootMap.node("volumes"), issues)
        validateDependencies(services)
        detectCycle(services)
        validatePortConflicts(services)

        return ComposeSpec(services = services, issues = issues, declaredVolumes = declaredVolumes)
    }

    // ---------------------------------------------------------------- 服务解析

    private fun parseService(
        name: String,
        node: YamlNode,
        issues: MutableList<ComposeIssue>,
        serviceLine: Int,
    ): ComposeService {
        val map = node as? YamlMap
            ?: throw ComposeParseException("service $name must be a key-value mapping", node.line(), node.column())

        map.entries.keys.forEach { key ->
            if (key.content !in SERVICE_KEYS) {
                issues += ComposeIssue(
                    service = name,
                    key = key.content,
                    kind = ComposeIssueKind.UNSUPPORTED_KEY,
                    message = "service $name: key \"${key.content}\" is not supported and was ignored",
                    line = key.line(),
                )
            }
        }

        val image = map.scalar("image")?.content?.trim().orEmpty()
        if (image.isEmpty()) {
            throw ComposeParseException(
                "service $name is missing image",
                map.node("image")?.line() ?: serviceLine,
                map.node("image")?.column(),
            )
        }

        val commandNode = map.node("command")
        val command: List<String>
        val commandShellForm: Boolean
        when (commandNode) {
            null, is YamlNull -> {
                command = emptyList()
                commandShellForm = false
            }

            is YamlScalar -> {
                command = listOf(commandNode.content)
                commandShellForm = true
            }

            is YamlList -> {
                command = commandNode.items.map { item -> item.requireScalar("service $name: command list items must be strings") }
                commandShellForm = false
            }

            else -> throw ComposeParseException(
                "service $name: unsupported command format",
                commandNode.line(),
                commandNode.column(),
            )
        }

        val entrypointNode = map.node("entrypoint")
        val entrypoint = when (entrypointNode) {
            null, is YamlNull -> emptyList()
            is YamlScalar -> listOf("/bin/sh", "-c", entrypointNode.content)
            is YamlList -> entrypointNode.items.map { item ->
                item.requireScalar("service $name: entrypoint list items must be strings")
            }

            else -> throw ComposeParseException(
                "service $name: unsupported entrypoint format",
                entrypointNode.line(),
                entrypointNode.column(),
            )
        }

        val environment = parseEnvironment(name, map.node("environment"), issues)
        val ports = parsePorts(name, map.node("ports"), issues)
        val volumes = parseVolumes(name, map.node("volumes"), issues)
        val dependsOn = parseDependsOn(name, map.node("depends_on"), issues)
        val restartPolicy = parseRestart(name, map.node("restart"), issues)

        val service = ComposeService(
            name = name,
            image = image,
            command = command,
            commandShellForm = commandShellForm,
            entrypoint = entrypoint,
            environment = environment,
            ports = ports,
            volumes = volumes,
            volumeMounts = namedMountsOf(volumes),
            dependsOn = dependsOn,
            restartPolicy = restartPolicy,
            line = serviceLine,
        )
        if (service.hasPid1Risk()) {
            issues += ComposeIssue(
                service = name,
                key = "entrypoint",
                kind = ComposeIssueKind.PID1_RISK,
                message = "service $name uses /init entrypoint (s6-overlay images rely on PID 1) and may fail to start under proot",
                line = node.line(),
            )
        }
        return service
    }

    private fun parseEnvironment(
        service: String,
        node: YamlNode?,
        issues: MutableList<ComposeIssue>,
    ): Map<String, String> {
        if (node == null || node is YamlNull) return emptyMap()
        val result = LinkedHashMap<String, String>()
        when (node) {
            is YamlMap -> node.entries.forEach { (key, value) ->
                result[key.content] = when (value) {
                    is YamlScalar -> value.content
                    is YamlNull -> ""
                    else -> throw ComposeParseException(
                        "service $service: environment value of \"${key.content}\" must be a string",
                        value.line(),
                        value.column(),
                    )
                }
            }

            is YamlList -> node.items.forEach { item ->
                val text = item.requireScalar("service $service: environment list items must be strings")
                val separator = text.indexOf('=')
                if (separator < 0) {
                    // compose 允许只写变量名（引用宿主环境）；App 内无宿主环境可引用，按空串处理并提示
                    result[text.trim()] = ""
                    issues += ComposeIssue(
                        service = service,
                        key = "environment",
                        kind = ComposeIssueKind.UNSUPPORTED_KEY,
                        message = "environment variable \"${text.trim()}\" has no value (host environment is unavailable); treated as empty",
                        line = item.line(),
                    )
                } else {
                    result[text.substring(0, separator).trim()] = text.substring(separator + 1)
                }
            }

            else -> throw ComposeParseException(
                "service $service: unsupported environment format",
                node.line(),
                node.column(),
            )
        }
        return result
    }

    private fun parsePorts(
        service: String,
        node: YamlNode?,
        issues: MutableList<ComposeIssue>,
    ): List<String> {
        if (node == null || node is YamlNull) return emptyList()
        val list = node as? YamlList
            ?: throw ComposeParseException("service $service: ports must be a list", node.line(), node.column())
        val ports = mutableListOf<String>()
        list.items.forEach { item ->
            when (item) {
                is YamlScalar -> ports += item.content
                else -> issues += ComposeIssue(
                    service = service,
                    key = "ports",
                    kind = ComposeIssueKind.UNSUPPORTED_KEY,
                    message = "service $service: long-syntax ports entries are not supported and were ignored",
                    line = item.line(),
                )
            }
        }
        if (ports.isNotEmpty()) {
            issues += ComposeIssue(
                service = service,
                key = "ports",
                kind = ComposeIssueKind.PORTS_HINT,
                message = "ports are not mapped (proot shares the host network stack): the service must listen on " +
                    "${ports.joinToString(", ")}; access it at http://127.0.0.1:<container port>",
                line = node.line(),
            )
        }
        return ports
    }

    /**
     * 顶层 `volumes:` 声明：只取名字集合（driver/external 等值忽略）。
     * 名字用于文档一致性；**隐式使用也允许**（直接丢一个 compose 文件进来也能用）。
     */
    private fun parseDeclaredVolumes(node: YamlNode?, issues: MutableList<ComposeIssue>): List<String> {
        if (node == null || node is YamlNull) return emptyList()
        val map = node as? YamlMap ?: run {
            issues += ComposeIssue(
                service = null,
                key = "volumes",
                kind = ComposeIssueKind.UNSUPPORTED_KEY,
                message = "top-level volumes must be a key-value mapping; declaration ignored",
                line = node.line(),
            )
            return emptyList()
        }
        return map.entries.keys.map { it.content }
    }

    private fun parseVolumes(
        service: String,
        node: YamlNode?,
        issues: MutableList<ComposeIssue>,
    ): List<String> {
        if (node == null || node is YamlNull) return emptyList()
        val list = node as? YamlList
            ?: throw ComposeParseException("service $service: volumes must be a list", node.line(), node.column())
        val volumes = mutableListOf<String>()
        val mounts = mutableListOf<VolumeMount>()
        list.items.forEach { item ->
            when (item) {
                is YamlScalar -> {
                    volumes += item.content
                    val entry = NamedVolumes.parseEntry(item.content)
                    when (entry.kind) {
                        NamedVolumes.VolumeEntryKind.NAMED -> entry.name?.let { name ->
                            entry.containerPath?.let { path -> mounts += VolumeMount(name, path) }
                        }

                        NamedVolumes.VolumeEntryKind.BIND_PATH -> issues += ComposeIssue(
                            service = service,
                            key = "volumes",
                            kind = ComposeIssueKind.VOLUME_BIND_IGNORED,
                            message = "service $service: host path volume \"${item.content}\" is not mounted; " +
                                "binding arbitrary host paths is not allowed for security",
                            line = item.line(),
                        )

                        NamedVolumes.VolumeEntryKind.ANONYMOUS -> issues += ComposeIssue(
                            service = service,
                            key = "volumes",
                            kind = ComposeIssueKind.VOLUMES_IGNORED,
                            message = "service $service: anonymous volume \"${item.content}\" is parsed only and not mounted",
                            line = item.line(),
                        )
                    }
                }

                else -> issues += ComposeIssue(
                    service = service,
                    key = "volumes",
                    kind = ComposeIssueKind.UNSUPPORTED_KEY,
                    message = "service $service: long-syntax volumes entries are not supported and were ignored",
                    line = item.line(),
                )
            }
        }
        return volumes
    }

    /** 服务声明里可挂载的 named volumes（与 [parseVolumes] 同源分类，避免二次解析）。 */
    internal fun namedMountsOf(volumes: List<String>): List<VolumeMount> =
        volumes.mapNotNull { raw ->
            val entry = NamedVolumes.parseEntry(raw)
            if (entry.kind == NamedVolumes.VolumeEntryKind.NAMED && entry.name != null && entry.containerPath != null) {
                VolumeMount(entry.name, entry.containerPath)
            } else {
                null
            }
        }

    /**
     * `restart` 策略：标量取 [RestartPolicies] 之一；长语法（`restart: {policy: always}`）
     * 取 `policy` 键、其余键（delay/max_attempts 等引擎未实现）忽略并提示。
     * 未知值不致命：记 issue 后按 `no`（返回 null）处理。
     */
    private fun parseRestart(
        service: String,
        node: YamlNode?,
        issues: MutableList<ComposeIssue>,
    ): String? {
        if (node == null || node is YamlNull) return null
        val raw = when (node) {
            is YamlScalar -> node.content.trim()
            is YamlMap -> {
                node.entries.keys
                    .filter { it.content != "policy" }
                    .forEach { key ->
                        issues += ComposeIssue(
                            service = service,
                            key = "restart",
                            kind = ComposeIssueKind.UNSUPPORTED_KEY,
                            message = "service $service: restart.\"${key.content}\" is not supported and was ignored",
                            line = key.line(),
                        )
                    }
                (node.node("policy") as? YamlScalar)?.content?.trim()
                    ?: run {
                        issues += ComposeIssue(
                            service = service,
                            key = "restart",
                            kind = ComposeIssueKind.UNSUPPORTED_KEY,
                            message = "service $service: restart long syntax without policy is ignored",
                            line = node.line(),
                        )
                        return null
                    }
            }

            else -> {
                issues += ComposeIssue(
                    service = service,
                    key = "restart",
                    kind = ComposeIssueKind.UNSUPPORTED_KEY,
                    message = "service $service: unsupported restart format; treated as \"no\"",
                    line = node.line(),
                )
                return null
            }
        }
        if (raw !in RestartPolicies.SUPPORTED) {
            issues += ComposeIssue(
                service = service,
                key = "restart",
                kind = ComposeIssueKind.UNSUPPORTED_KEY,
                message = "service $service: restart \"$raw\" is not supported (use no / always / unless-stopped / on-failure); treated as \"no\"",
                line = node.line(),
            )
            return null
        }
        return raw
    }

    private fun parseDependsOn(
        service: String,
        node: YamlNode?,
        issues: MutableList<ComposeIssue>,
    ): List<String> {
        if (node == null || node is YamlNull) return emptyList()
        return when (node) {
            is YamlList -> node.items.map { item ->
                item.requireScalar("service $service: depends_on list items must be service names")
            }

            is YamlMap -> {
                issues += ComposeIssue(
                    service = service,
                    key = "depends_on",
                    kind = ComposeIssueKind.DEPENDS_ON_CONDITION,
                    message = "service $service: depends_on long syntax (condition) is not supported; " +
                        "startup order and port readiness wait are applied",
                    line = node.line(),
                )
                node.entries.keys.map { it.content }
            }

            else -> throw ComposeParseException(
                "service $service: unsupported depends_on format",
                node.line(),
                node.column(),
            )
        }
    }

    // ---------------------------------------------------------------- 校验

    private fun validateDependencies(services: List<ComposeService>) {
        val names = services.map { it.name }.toSet()
        services.forEach { service ->
            service.dependsOn.forEach { dependency ->
                if (dependency !in names) {
                    throw ComposeParseException(
                        "service ${service.name} depends on unknown service $dependency",
                        service.line,
                    )
                }
            }
        }
    }

    private fun detectCycle(services: List<ComposeService>) {
        val cyclic = ComposeSpec(services).cyclicServices()
        if (cyclic.isNotEmpty()) {
            throw ComposeParseException(
                "dependency cycle detected: ${cyclic.joinToString(" → ")}",
                services.firstOrNull { it.name == cyclic.first() }?.line,
            )
        }
    }

    /**
     * 容器端口冲突静态检查（致命错误，保存/编辑/up 全链路拦截）。
     *
     * proot 与手机共享宿主网络栈、端口不做映射（方案 §3.5）：**容器内端口即手机端口**，
     * 两个服务声明同一「容器端口 + 协议」时后启动者必 bind 失败——docker 里靠端口映射能并存，
     * 这里不能，所以在解析期就拦下。比较的是容器端口（`"8080:80"` 与 `"8081:80"` 冲突），
     * 协议不同（tcp/udp）不算冲突；同一服务内重复声明不拦。
     */
    private fun validatePortConflicts(services: List<ComposeService>) {
        val firstOwner = HashMap<Pair<Int, String>, String>()
        services.forEach { service ->
            service.ports.forEach { raw ->
                val port = raw.containerPortValue() ?: return@forEach
                val proto = raw.substringAfter('/', "tcp").trim().lowercase().ifEmpty { "tcp" }
                val key = port to proto
                val owner = firstOwner[key]
                if (owner != null && owner != service.name) {
                    throw ComposeParseException(
                        "port conflict: services $owner and ${service.name} both declare container port " +
                            "$port/$proto; proot shares the host network stack (no port mapping), " +
                            "so only one service can bind it",
                        service.line,
                    )
                }
                if (owner == null) firstOwner[key] = service.name
            }
        }
    }

    // ---------------------------------------------------------------- kaml 适配

    private fun parseNode(yaml: String): YamlNode = try {
        Yaml.default.parseToYamlNode(yaml)
    } catch (e: YamlException) {
        // kaml 的行/列号是 1 基，可直接展示
        throw ComposeParseException(e.message, e.line, e.column)
    } catch (e: Exception) {
        throw ComposeParseException(e.message ?: "YAML parse failed")
    }

    // 节点访问工具（node / scalar / requireScalar / line / column）见 YamlNodes.kt：与模板目录解析共用一套
}
