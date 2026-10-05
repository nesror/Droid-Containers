package cn.yzapp.androidcontainer.core.data

import cn.yzapp.androidcontainer.core.common.DefaultDispatcherProvider
import cn.yzapp.androidcontainer.core.data.db.ComposeProjectEntity
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.data.db.InventoryDao
import cn.yzapp.androidcontainer.core.engine.compose.ComposeIssue
import cn.yzapp.androidcontainer.core.engine.compose.ComposeIssueKind
import cn.yzapp.androidcontainer.core.engine.compose.ComposeParseException
import cn.yzapp.androidcontainer.core.engine.compose.ComposeParser
import cn.yzapp.androidcontainer.core.engine.compose.ComposeService
import cn.yzapp.androidcontainer.core.engine.compose.ComposeSpec
import cn.yzapp.androidcontainer.core.engine.compose.NamedVolumes
import cn.yzapp.androidcontainer.core.engine.compose.ReadinessProbe
import cn.yzapp.androidcontainer.core.engine.compose.VolumeMount
import cn.yzapp.androidcontainer.core.engine.compose.containerPortValue
import cn.yzapp.androidcontainer.core.engine.proot.BindMount
import cn.yzapp.androidcontainer.core.engine.proot.ProotRuntime
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 项目聚合状态（各服务容器运行态的汇总，方案 §3.1）。
 * [STARTING] 不是容器状态的推导结果，而是 UI 侧「up 执行中（含镜像拉取）」的瞬态覆盖，
 * 由 ViewModel 在 busy 期间临时代入，不参与 [composeProjectState] 的纯函数推导。
 */
enum class ComposeProjectState { NOT_CREATED, RUNNING, PARTIAL, STOPPED, STARTING }

/** up 结果：启动的服务名 + 解析/运行时提示（不含致命错误——那种情况直接抛异常）。 */
data class ComposeUpReport(
    val projectName: String,
    val startedServices: List<String>,
    val issues: List<ComposeIssue>,
)

/**
 * 编排门面（方案 §3.1 阶段一）：
 * - 项目 CRUD（只存 YAML 原文，解析产物不落库）；
 * - `up`：缺失镜像自动拉取 → depends_on 拓扑序逐个创建并启动；中途失败回滚本次已启动的服务；
 * - `down`：启动顺序的反向停止，可选连容器一起删除；
 * - 聚合状态由 [composeProjectState] 纯函数计算，UI 直接消费。
 */
class ComposeRepository(
    private val dao: InventoryDao,
    private val containers: ContainerRepository,
    /** 镜像仓库：up 时镜像缺失可自动拉取；为 null 时保持"缺镜像即报错"旧行为。 */
    private val images: ImageRepository? = null,
    /** 引擎目录：named volume 的宿主存储根（`volumes/<name>`）。 */
    private val engineDir: File? = null,
) {

    private val dispatchers = DefaultDispatcherProvider()

    /**
     * 项目级生命周期互斥：up / down / deleteProject 共用（审查 P1-13）。
     * 之前 up 用 upMutex、down/deleteProject 用 downMutex，「up 与 down 互斥」实际
     * 不成立——同一容器可能同时被 start 与 stop/remove；合并为单锁后天然互斥
     * （不同项目也会串行，编排操作低频，可接受）。
     */
    private val projectMutex = Mutex()

    fun observeProjects(): Flow<List<ComposeProjectEntity>> = dao.observeComposeProjects()

    fun observeContainers(): Flow<List<ContainerEntity>> = containers.observeContainers()

    /** 解析 YAML；失败抛 [ComposeParseException]（含行号）。 */
    fun parse(yaml: String): ComposeSpec = ComposeParser.parse(yaml)

    /** 容错解析：项目卡片只展示服务数，YAML 坏了不应让整页失联。 */
    fun parseOrNull(yaml: String): ComposeSpec? = runCatching { ComposeParser.parse(yaml) }.getOrNull()

    suspend fun findProject(id: String): ComposeProjectEntity? = withContext(dispatchers.io) {
        dao.findComposeProject(id)
    }

    suspend fun createProject(name: String, yaml: String): ComposeProjectEntity = withContext(dispatchers.io) {
        validate(name, yaml)
        val entity = ComposeProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            yamlContent = yaml,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertComposeProject(entity)
        entity
    }

    suspend fun updateProject(id: String, name: String, yaml: String): ComposeProjectEntity =
        withContext(dispatchers.io) {
            validate(name, yaml)
            val existing = dao.findComposeProject(id)
                ?: throw EngineException(EngineErrorCode.COMPOSE_INVALID, "project not found: $id")
            val entity = existing.copy(name = name.trim(), yamlContent = yaml)
            dao.upsertComposeProject(entity)
            entity
        }

    /** 删除项目：按启动顺序反向停止其容器，[removeContainers] 为真时连容器库存一起删除。 */
    suspend fun deleteProject(id: String, removeContainers: Boolean): Unit = withContext(dispatchers.io) {
        projectMutex.withLock {
            val project = dao.findComposeProject(id) ?: return@withLock
            stopProjectContainers(project, removeContainers)
            dao.deleteComposeProject(project.id)
        }
    }

    /**
     * 一键 up：缺失镜像自动拉取（多服务共用镜像只拉一次），按 depends_on 顺序启动；
     * 任一服务启动失败则回滚本次已启动的服务，避免留下半拉子状态。
     */
    suspend fun up(projectId: String): ComposeUpReport = withContext(dispatchers.io) {
        projectMutex.withLock {
            val project = dao.findComposeProject(projectId)
                ?: throw EngineException(EngineErrorCode.COMPOSE_INVALID, "project not found: $projectId")
            val spec = parseOrThrow(project.yamlContent)

            // 镜像缺失 → 自动拉取（顺序执行，同一镜像只拉一次；进度见「镜像」页）
            spec.services.forEach { service ->
                if (dao.findImage(service.image) == null) {
                    images?.pull(service.image)
                        ?: throw EngineException(
                            EngineErrorCode.IMAGE_MISSING,
                            "image ${service.image} is not pulled yet; pull it on the Images tab first (service ${service.name})",
                        )
                }
            }

            val issues = spec.issues.toMutableList()
            val startedContainers = mutableListOf<ContainerEntity>()
            try {
                spec.startupSequence().forEach { serviceName ->
                    val service = spec.service(serviceName)
                        ?: throw EngineException(EngineErrorCode.COMPOSE_INVALID, "service $serviceName is missing from the parsed spec")
                    val container = containers.upsertServiceContainer(
                        projectId = project.id,
                        projectName = project.name,
                        serviceName = service.name,
                        imageRef = service.image,
                        command = service.resolvedArgv(),
                        environment = service.environment,
                        // 正文声明的容器端口随新容器写入：容器页「浏览器」与编排详情直达开箱即用
                        httpPorts = service.ports.mapNotNull { it.containerPortValue() }.distinct(),
                        // named volume binds 随容器持久化（mountsJson）：重启/autoStart 后仍生效，
                        // 否则 bind 丢失会让服务重新写到 rootfs 老路径，造成数据分裂
                        volumeBinds = prepareVolumeBinds(service),
                        // restart 策略（HA 网页端「重启」等主进程自退场景的自动拉起）
                        restartPolicy = service.restartPolicy,
                    )
                    containers.start(container.id)
                    startedContainers += container
                    issues += runtimeIssuesOf(container, service)
                    issues += awaitReadiness(spec, service)
                }
            } catch (e: Exception) {
                // 回滚：反向停止本次已启动的服务（先前本就运行的不动）
                startedContainers.reversed().forEach { container ->
                    runCatching { containers.stop(container.id) }
                }
                throw e
            }

            ComposeUpReport(
                projectName = project.name,
                startedServices = startedContainers.mapNotNull { it.serviceName },
                issues = issues,
            )
        }
    }

    /** 一键 down：启动顺序的反向停止；[removeContainers] 为真时同时删除容器库存。 */
    suspend fun down(projectId: String, removeContainers: Boolean): List<String> = withContext(dispatchers.io) {
        projectMutex.withLock {
            val project = dao.findComposeProject(projectId)
                ?: throw EngineException(EngineErrorCode.COMPOSE_INVALID, "project not found: $projectId")
            stopProjectContainers(project, removeContainers)
        }
    }

    // ------------------------------------------------------------ 内部实现

    private suspend fun stopProjectContainers(
        project: ComposeProjectEntity,
        removeContainers: Boolean,
    ): List<String> {
        // YAML 可能已被改坏，停止路径用容错解析，拿不到顺序就按创建顺序倒序
        val spec = parseOrNull(project.yamlContent)
        val sequence = spec?.shutdownSequence().orEmpty()
        val projectContainers = containers.containersOfProject(project.id)
        val ordered = projectContainers.sortedBy { container ->
            val index = sequence.indexOf(container.serviceName)
            if (index < 0) Int.MAX_VALUE else index
        }

        val stopped = mutableListOf<String>()
        ordered.forEach { container ->
            if (containers.isRunning(container.id)) {
                runCatching { containers.stop(container.id) }
                stopped += container.name
            }
            if (removeContainers) {
                runCatching { containers.remove(container.id) }
            }
        }
        return stopped
    }

    /**
     * 就绪等待（depends_on 升级，方案 §3.2）：被其他服务依赖且声明了端口的服务启动后，
     * 等待端口开口再继续拉起依赖方（docker 的 condition: service_healthy 的实用近似——
     * proot 共享宿主网络栈，App 内 TCP connect 127.0.0.1 即等价服务就绪）。
     * 超时**不回滚**、记 READINESS_TIMEOUT 提示后继续（首启慢的服务不该让整个 up 失败，
     * 依赖方连不上时会以自身错误暴露真实原因）。
     */
    private suspend fun awaitReadiness(
        spec: ComposeSpec,
        service: ComposeService,
    ): List<ComposeIssue> {
        val isDependedOn = spec.services.any { it.name != service.name && service.name in it.dependsOn }
        if (!isDependedOn) return emptyList()
        val ports = service.ports.mapNotNull { it.containerPortValue() }.distinct()
        if (ports.isEmpty()) return emptyList()
        val ready = ReadinessProbe.awaitPortOpen(ports, ReadinessProbe.DEFAULT_TIMEOUT_MS)
        if (ready != null) return emptyList()
        return listOf(
            ComposeIssue(
                service = service.name,
                key = "depends_on",
                kind = ComposeIssueKind.READINESS_TIMEOUT,
                message = "service ${service.name} did not open port(s) ${ports.joinToString(", ")} within " +
                    "${ReadinessProbe.DEFAULT_TIMEOUT_MS / 1000}s; continuing — dependent services may fail to connect",
                line = service.line,
            ),
        )
    }

    /**
     * named volume 挂载准备（up 内，每服务启动前）：
     * ① `volumes/<name>` 目录就绪；② **copy-on-first-use 老数据迁移**——volume 为空且
     * rootfs 内对应容器路径已有数据（历史版本数据寄生 rootfs）时自动拷入；
     * ③ 返回 binds 编码（存 mountsJson，start() 时还原）。engineDir 未配置时返回空。
     */
    private suspend fun prepareVolumeBinds(service: ComposeService): List<String> {
        if (service.volumeMounts.isEmpty()) return emptyList()
        val engine = engineDir ?: return emptyList()
        val rootfs = dao.findImage(service.image)?.let { File(it.rootfsPath) }
        val binds = mutableListOf<String>()
        service.volumeMounts.forEach { mount: VolumeMount ->
            val volumeDir = NamedVolumes.volumeDirFor(engine, mount.name).apply { mkdirs() }
            val migrated = if (rootfs != null && rootfs.isDirectory) {
                NamedVolumes.copyOnFirstUse(rootfs, mount.containerPath, volumeDir)
            } else {
                false
            }
            if (migrated) {
                AppLogger.i("Compose", "volume ${mount.name}: migrated existing data from rootfs ${mount.containerPath}")
            }
            binds += "${volumeDir.path}:${mount.containerPath}"
        }
        return binds
    }

    /** 运行时提示：镜像入口依赖 PID 1（s6-overlay 等）时给出更具体的说明（方案 §3.3）。 */
    private suspend fun runtimeIssuesOf(
        container: ContainerEntity,
        service: ComposeService,
    ): List<ComposeIssue> {
        val rootfs = dao.findImage(service.image)?.let { File(it.rootfsPath) } ?: return emptyList()
        if (!service.hasPid1Risk()) return emptyList()
        return if (ProotRuntime.isS6OverlayEntrypoint(rootfs, "/init")) {
            listOf(
                ComposeIssue(
                    service = service.name,
                    key = "entrypoint",
                    kind = ComposeIssueKind.PID1_RISK,
                    message = "service ${service.name} uses s6-overlay service scripts (etc/services.d/*/run); " +
                        "proot cannot provide PID 1, so startup will likely fail — consider starting its " +
                        "foreground process with an explicit command",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun validate(name: String, yaml: String) {
        if (name.isBlank()) {
            throw EngineException(EngineErrorCode.COMPOSE_INVALID, "project name is blank")
        }
        parseOrThrow(yaml)
    }

    private fun parseOrThrow(yaml: String): ComposeSpec = try {
        ComposeParser.parse(yaml)
    } catch (e: ComposeParseException) {
        throw EngineException(EngineErrorCode.COMPOSE_INVALID, e.displayMessage, e)
    }
}

/**
 * 项目聚合状态（方案 §3.1）：全部服务运行中 = RUNNING；部分 = PARTIAL；无运行 = STOPPED；无容器 = NOT_CREATED。
 */
fun composeProjectState(
    serviceCount: Int,
    projectContainers: List<ContainerEntity>,
    runtime: Map<String, ContainerRuntime>,
): ComposeProjectState {
    if (projectContainers.isEmpty()) return ComposeProjectState.NOT_CREATED
    val running = projectContainers.count { runtime[it.id] == ContainerRuntime.RUNNING }
    return when {
        running == 0 -> ComposeProjectState.STOPPED
        running == projectContainers.size && projectContainers.size >= serviceCount ->
            ComposeProjectState.RUNNING

        else -> ComposeProjectState.PARTIAL
    }
}
