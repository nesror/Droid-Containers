package cn.yzapp.androidcontainer.core.engine.compose

/**
 * 解析失败（结构 / 语义致命错误）。line / column 为可直接展示给用户的 1 基行号。
 */
class ComposeParseException(
    message: String,
    val line: Int? = null,
    val column: Int? = null,
) : Exception(message) {

    /** 供 UI 直接拼装的错误文案：`<message> (line N)`。 */
    val displayMessage: String
        get() = if (line == null) message.orEmpty() else "${message.orEmpty()} (line $line)"
}

/**
 * 非致命提示：不支持/被忽略的键、语义差异（方案 §3.1 阶段一第 2 项「不支持的键显式标记」）。
 */
enum class ComposeIssueKind {
    /** 未实现的 compose 键，已忽略。 */
    UNSUPPORTED_KEY,

    /** ports 仅作提示（proot 共享宿主网络栈，无端口映射，方案 §3.5）。 */
    PORTS_HINT,

    /** volumes 阶段一仅解析不挂载。 */
    VOLUMES_IGNORED,

    /**
     * bind 路径 volume（`/abs:/path`）不挂载：宿主任意路径 bind 有隐私攻击面
     * （恶意 compose 可指向 App 私有目录），scoped storage 亦挡住公共目录。
     */
    VOLUME_BIND_IGNORED,

    /** depends_on 长语法 condition 不支持，仅保证启动顺序。 */
    DEPENDS_ON_CONDITION,

    /**
     * 被依赖服务在超时窗口内端口未就绪（up 继续执行，依赖方可能连不上）。
     */
    READINESS_TIMEOUT,

    /** 入口为 /init（s6-overlay 类）等依赖 PID 1 的镜像，proot 下可能无法启动。 */
    PID1_RISK,
}

data class ComposeIssue(
    /** 所属服务名；顶层问题为 null。 */
    val service: String?,
    val key: String,
    val kind: ComposeIssueKind,
    val message: String,
    val line: Int? = null,
)

/**
 * 单个 service 的解析结果（阶段一支持键：image / command / entrypoint / environment / ports / volumes / depends_on）。
 */
data class ComposeService(
    val name: String,
    val image: String,
    /** list 形式为参数列表；scalar（shell 形式）时仅含一个元素，配合 [commandShellForm]。 */
    val command: List<String> = emptyList(),
    val commandShellForm: Boolean = false,
    val entrypoint: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    /** 仅作提示，不参与启动（方案 §3.5）。 */
    val ports: List<String> = emptyList(),
    /** 原样保留的 volumes 声明；named volume 的挂载信息见 [volumeMounts]。 */
    val volumes: List<String> = emptyList(),
    /**
     * named volume 挂载（up 时 bind `engineDir/volumes/<name>` → [containerPath]）。
     * bind 路径与匿名卷不在此列（分别报 VOLUME_BIND_IGNORED / VOLUMES_IGNORED）。
     */
    val volumeMounts: List<VolumeMount> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    /**
     * compose `restart` 策略（[RestartPolicies] 取值；null = 未声明，按 `no` 处理）。
     * 语义见 [RestartPolicies] 文档。
     */
    val restartPolicy: String? = null,
    val line: Int? = null,
) {

    /**
     * 容器内实际 argv，对齐 docker 语义：
     * - 有 entrypoint → entrypoint 覆盖镜像入口，command 作为其参数追加；
     * - 无 entrypoint 且 command 为 shell 形式 → `/bin/sh -c <command>`；
     * - 其余 → command 原样。
     */
    fun resolvedArgv(): List<String> = when {
        entrypoint.isNotEmpty() -> entrypoint + command
        commandShellForm && command.isNotEmpty() -> listOf("/bin/sh", "-c", command.first())
        else -> command
    }

    /** 是否命中 PID 1 风险（/init 入口，方案 §3.3）。 */
    fun hasPid1Risk(): Boolean = resolvedArgv().any { it.trim() == "/init" }
}

/**
 * 一份 compose 文件的解析结果。services 保持文件声明顺序；依赖顺序由 [startupSequence] 计算。
 */
data class ComposeSpec(
    val services: List<ComposeService>,
    val issues: List<ComposeIssue> = emptyList(),
    /** 顶层 `volumes:` 声明的名字集合（值如 driver/external 忽略；隐式使用也允许，对齐容错哲学）。 */
    val declaredVolumes: List<String> = emptyList(),
) {

    val serviceNames: List<String> get() = services.map { it.name }

    fun service(name: String): ComposeService? = services.firstOrNull { it.name == name }

    /**
     * depends_on 拓扑分层（同层无相互依赖，可并行）。成环时该层之后的服务不会出现在结果里，
     * 由 [cyclicServices] 单独暴露（解析阶段已把它们判为致命错误，这里只是不让调用方拿到半成品顺序）。
     */
    fun startupOrder(): List<List<String>> {
        val order = services.withIndex().associate { (index, service) -> service.name to index }
        val deps = services.associate { it.name to it.dependsOn.toSet() }
        val pending = services.map { it.name }.toMutableList()
        val finished = mutableSetOf<String>()
        val levels = mutableListOf<List<String>>()

        while (pending.isNotEmpty()) {
            val ready = pending
                .filter { name -> deps[name].orEmpty().all { it in finished } }
                .sortedBy { order[it] }
            if (ready.isEmpty()) break
            levels += ready
            finished += ready
            pending.removeAll(ready.toSet())
        }
        return levels
    }

    /** 因依赖成环/自引用而无法定序的服务（正常文档为空）。 */
    fun cyclicServices(): List<String> {
        val ordered = startupOrder().flatten().toSet()
        return services.map { it.name }.filter { it !in ordered }
    }

    /** 展平的启动顺序（阶段一顺序启动）。 */
    fun startupSequence(): List<String> = startupOrder().flatten()

    /** 停止顺序：启动顺序的反向（方案 §3.1）。 */
    fun shutdownSequence(): List<String> = startupSequence().reversed()
}

/**
 * 所有 service 声明的容器内端口（去重、保持声明顺序）。
 *
 * proot 与手机共享网络栈、端口不做映射，因此**容器内端口就是手机／局域网可直接访问的端口**（方案 §3.5）。
 * 模板详情、容器端口的「直达」链接与远程控制台都依赖这一份派生逻辑。
 */
val ComposeSpec.containerPorts: List<Int>
    get() = services.flatMap { it.ports }.mapNotNull { it.containerPortValue() }.distinct()

/** `"8080:8080"` / `"53:53/udp"` / `"80"` → 容器内端口；解析不出数字时返回 null。 */
fun String.containerPortValue(): Int? =
    substringBefore('/').substringAfterLast(':').trim().toIntOrNull()

/** 一次 named volume 挂载（服务 YAML 的 `name:/container/path`）。 */
data class VolumeMount(val name: String, val containerPath: String)

/**
 * compose `restart` 策略（docker 语义子集）。
 *
 * 引擎实现约定（见 ContainerRepository 的自动重启）：
 * - `no`（缺省）：主进程自然退出后保持停止；
 * - `always` / `unless-stopped`：主进程自然退出（HA 网页端「重启」即此类）后自动按退避拉起，
 *   用户显式 stop / down / 停止全部后不再拉起；
 * - `on-failure`：引擎拿不到退出码（统一记 0），按「自然退出即重启」近似实现，与 unless-stopped 等同。
 */
object RestartPolicies {
    const val NO = "no"
    const val ALWAYS = "always"
    const val UNLESS_STOPPED = "unless-stopped"
    const val ON_FAILURE = "on-failure"

    /** 引擎可识别的全部标量取值；其余值解析期记 issue 并按 `no` 处理。 */
    val SUPPORTED = setOf(NO, ALWAYS, UNLESS_STOPPED, ON_FAILURE)

    /** 自然退出后需要自动拉起的策略集合。 */
    val AUTO_RESTART = setOf(ALWAYS, UNLESS_STOPPED, ON_FAILURE)
}

/**
 * 容器命名（方案 §3.1：`project-service`）。
 */
object ComposeNaming {

    fun containerName(projectName: String, serviceName: String): String =
        "${sanitize(projectName)}-${sanitize(serviceName)}"

    /** 容器名限定小写字母/数字/`-`/`_`/`.`，其余替换为 `-`（对齐 docker 命名规则）。 */
    fun sanitize(raw: String): String {
        val mapped = raw.trim().lowercase().map { ch ->
            if (ch.isLetterOrDigit() && ch.code < 128 || ch == '-' || ch == '_' || ch == '.') ch else '-'
        }.joinToString("")
        return mapped.trim('-').ifEmpty { "project" }
    }
}
