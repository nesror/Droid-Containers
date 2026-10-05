package cn.yzapp.androidcontainer.core.data

import android.content.Context
import android.os.Build
import cn.yzapp.androidcontainer.core.common.DefaultDispatcherProvider
import cn.yzapp.androidcontainer.core.common.detectHostAbi
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.data.db.ImageEntity
import cn.yzapp.androidcontainer.core.data.db.InventoryDao
import cn.yzapp.androidcontainer.core.engine.oci.ImageDefaultEntry
import cn.yzapp.androidcontainer.core.engine.proot.ContainerManager
import cn.yzapp.androidcontainer.core.engine.proot.ProotRuntime
import cn.yzapp.androidcontainer.core.engine.compose.ComposeNaming
import cn.yzapp.androidcontainer.core.engine.compose.NamedVolumes
import cn.yzapp.androidcontainer.core.engine.compose.RestartPolicies
import cn.yzapp.androidcontainer.core.engine.terminal.TerminalSession
import cn.yzapp.androidcontainer.core.engine.terminal.TerminalSessionManager
import cn.yzapp.androidcontainer.core.model.ContainerStatus
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.PullStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 容器运行时状态（内存态；DB 只存最后已知状态）。 */
enum class ContainerRuntime { RUNNING, STOPPED }

/**
 * 容器生命周期事件（M8/M9 地基：Docker /events 流与 Web 实时刷新共用）。
 * type: start / die / stop / destroy / create
 */
data class ContainerEvent(
    val type: String,
    val containerId: String,
    val name: String,
    val imageRef: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * 容器仓库：创建（向导产物）→ 启动（proot）→ 停止 → 日志（方案 §4/§5）。
 */
class ContainerRepository(
    context: Context,
    private val dao: InventoryDao,
    private val engineDir: File,
    private val settings: SettingsRepository,
    /** 镜像仓库：创建容器时镜像缺失可自动拉取；为 null 时保持"未拉取即报错"旧行为。 */
    private val images: ImageRepository? = null,
) {

    private val appContext = context.applicationContext
    private val dispatchers = DefaultDispatcherProvider()

    private val manager = ContainerManager(
        engineDir = engineDir,
        ioDispatcher = dispatchers.io,
    )
    private val runtime = ProotRuntime(
        nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir),
        engineDir = engineDir,
    )
    private val terminals = TerminalSessionManager()

    /**
     * 生命周期互斥锁：start / stop / remove 共用。
     * 只保护 start 会漏掉跨操作竞态（start 读到 entity 后 remove 删行，start 又把
     * 已删除的容器 upsert 回去「复活」；isRunning 与 pidOf 之间进程退出 → 强解包 NPE），
     * 因此三条写路径必须都在同一把锁内。
     */
    private val lifecycleMutex = Mutex()

    private val _runtimeStates = MutableStateFlow<Map<String, ContainerRuntime>>(emptyMap())
    val runtimeStates: StateFlow<Map<String, ContainerRuntime>> = _runtimeStates.asStateFlow()

    private val _events = MutableSharedFlow<ContainerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ContainerEvent> = _events.asSharedFlow()

    private fun emitEvent(type: String, container: ContainerEntity) {
        _events.tryEmit(
            ContainerEvent(type, container.id, container.name, container.imageRef),
        )
    }

    /** 进程退出状态同步专用（仓库为 App 级单例，与进程同生命周期）。 */
    private val exitSyncScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /**
     * 期望运行状态（id → 最近一次成功 start 的时间戳）：restart 策略的判据。
     * start 成功写入；用户显式 stop / remove / down / 停止全部时移除——主进程随后退出时
     * 不再自动拉起（docker restart 策略对显式停止的容器同样不生效）。
     */
    private val desiredRunning = ConcurrentHashMap<String, Long>()

    /** 连续自动重启次数（退避用）；进程稳定运行超过 [RESTART_STABLE_MS] 后清零。 */
    private val restartAttempts = ConcurrentHashMap<String, Int>()

    init {
        // proot 进程自行退出（shell exit / 崩溃 / 外部杀进程）时校正内存态与 DB，
        // 避免 UI 仍显示运行中、点停止报 "is not running"。
        // ⚠️ 回调是异步的：退出后容器可能立刻被重新 start（入口脚本秒退后重试、编排
        // readiness 重试），此时新进程已写入 RUNNING。因此回调必须先确认该 id 没有被
        // 新进程占用，否则会把新进程的状态误改成 STOPPED（内存态与 DB 双写都要拦）。
        manager.onProcessExit = { id ->
            AppLogger.w(TAG, "container process exited id=$id")
            exitSyncScope.launch {
                if (manager.isRunning(id)) return@launch
                _runtimeStates.update { it + (id to ContainerRuntime.STOPPED) }
                val entity = dao.findContainer(id)
                entity?.let {
                    if (it.status == ContainerStatus.RUNNING) {
                        dao.upsertContainer(it.copy(status = ContainerStatus.STOPPED))
                    }
                    // proot 自行退出 → Docker 语义的 die 事件（退出码无区分，统一 0）
                    _events.tryEmit(ContainerEvent("die", it.id, it.name, it.imageRef))
                }
                // restart 策略（docker 语义子集）：期望仍在运行且策略允许 → 退避后自动拉起
                maybeAutoRestart(id, entity)
            }
        }
    }

    fun observeContainers(): Flow<List<ContainerEntity>> = dao.observeContainers()

    /**
     * 创建容器（向导产物）：校验镜像可用（未拉取时自动拉取，进度见「镜像」页），写入库存；不立即启动。
     */
    suspend fun create(
        name: String,
        imageRef: String,
        entryCommand: List<String>,
        autoStart: Boolean = false,
    ): ContainerEntity = withContext(dispatchers.io) {
        if (name.isBlank()) {
            throw EngineException(EngineErrorCode.START_FAILED, "container name is blank")
        }
        // 重名校验（与 rename 一致）：resolveContainerRef 按名解析取第一个命中，
        // 同名容器会让 Docker API / Web 控制台操作到「错误的那一个」（审查 P1-17）
        if (dao.observeContainers().first().any { it.name == name.trim() }) {
            throw EngineException(EngineErrorCode.START_FAILED, "name '${name.trim()}' already in use")
        }
        if (dao.findImage(imageRef) == null) {
            // 镜像缺失 → 自动拉取（多镜像源回退由 PullEngine 编排）；pull 成功后记录已入库
            images?.pull(imageRef)
            dao.findImage(imageRef)
                ?: throw EngineException(
                    EngineErrorCode.START_FAILED,
                    "image $imageRef pulled but record missing",
                )
        }
        val entity = ContainerEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            imageRef = imageRef,
            status = ContainerStatus.CREATED,
            autoStart = autoStart,
            createdAt = System.currentTimeMillis(),
            // docker create 的 Cmd 必须落库，否则启动时静默回落镜像默认入口（审查 P1-16）
            cmdJson = ContainerPayloads.encodeList(entryCommand),
        )
        dao.upsertContainer(entity)
        emitEvent("create", entity)
        entity
    }

    /** 启动容器：proot 进程 + 日志重写；幂等（已运行直接返回）。 */
    suspend fun start(id: String): Int = withContext(dispatchers.io) {
        try {
            lifecycleMutex.withLock {
                // 先取 pid 再判空，避免 isRunning 与 pidOf 之间进程恰好退出导致强解包 NPE；
                // 若表项已消失（刚退出），按未运行处理走完整启动流程
                val existingPid = if (manager.isRunning(id)) manager.pidOf(id) else null
                if (existingPid != null) {
                    AppLogger.i(TAG, "start id=$id already running pid=$existingPid")
                    // 幂等路径同样补记期望运行（restart 判据 + 进程重启自恢复），
                    // 否则重复 start 后进程退出不会按策略自动拉起
                    desiredRunning[id] = System.currentTimeMillis()
                    dao.findContainer(id)?.let {
                        if (!it.desiredRunning) dao.upsertContainer(it.copy(desiredRunning = true))
                    }
                    return@withLock existingPid
                }
                AppLogger.i(TAG, "start id=$id requested")
                val container = dao.findContainer(id)
                    ?: throw EngineException(EngineErrorCode.START_FAILED, "container $id not found")
                val image = dao.findImage(container.imageRef)
                    ?: throw EngineException(EngineErrorCode.START_FAILED, "image ${container.imageRef} missing")
                val rootfs = File(image.rootfsPath)
                if (!rootfs.isDirectory) {
                    throw EngineException(EngineErrorCode.START_FAILED, "rootfs missing: ${rootfs.path}")
                }

                val hostAbi = detectHostAbi(Build.SUPPORTED_ABIS.toList())
                    ?: throw EngineException(EngineErrorCode.UNSUPPORTED_DEVICE, "No 64-bit ABI")

                // DNS 设置 → 写 resolv.conf 并只读绑进容器（方案 §5）
                val dns = settings.dns.first()
                val bindsDir = File(engineDir, "binds").apply { mkdirs() }
                val resolv = File(bindsDir, "resolv.conf").apply { writeText("nameserver $dns\n") }
                // named volume binds：从 mountsJson 还原（compose up 时写入，重启/autoStart 后仍生效）
                val volumeBinds = NamedVolumes.decodeMounts(ContainerPayloads.decodeList(container.mountsJson))

                // compose 服务可能指定了 command / environment（方案 §3.1）；未指定则回落镜像默认入口
                val command = ContainerPayloads.commandOf(container).ifEmpty { defaultCommand(rootfs) }
                // OCI WorkingDir（docker 语义）：镜像声明了 cwd 就用它（halo 的相对路径 jar），
                // 否则回落引擎默认根目录；终端会话仍用根目录（login shell 自行落 home）
                val workdir = ImageDefaultEntry.workingDirFromConfig(File(rootfs.parentFile, "config"))
                    ?: DEFAULT_WORKDIR
                val spec = runtime.launch(
                    rootfs = rootfs,
                    workdir = workdir,
                    command = command,
                    userBinds = listOf(
                        cn.yzapp.androidcontainer.core.engine.proot.BindMount(
                            hostPath = resolv,
                            containerPath = "/etc/resolv.conf",
                            readOnly = true,
                        ),
                    ) + volumeBinds,
                )
                // 环境变量优先级（docker 语义）：镜像 config ENV < 用户/compose 环境 < 引擎注入；
                // 镜像 ENV 承载镜像默认 PATH/HOME 等（syncthing 的 HOME 缺失会让 entrypoint 直接退出）
                val imageEnv = ImageDefaultEntry.envFromConfig(File(rootfs.parentFile, "config"))
                // 用户环境变量在前、proot 运行环境在后：LD_LIBRARY_PATH / PROOT_* 必须由引擎胜出；
                // PATH 例外（对齐 docker）：compose > 镜像 ENV > 引擎默认 —— 镜像自带 PATH
                // （如 halo 的 /opt/java/openjdk/bin）被引擎标准 PATH 覆盖会让入口脚本 exec java not found
                val userEnv = ContainerPayloads.environmentOf(container)
                val engineEnv = spec.environment.toMutableMap()
                (userEnv["PATH"] ?: imageEnv["PATH"])?.let { engineEnv["PATH"] = it }
                val environment = imageEnv + userEnv + engineEnv
                val pid = manager.start(id, spec.argv, environment)
                _runtimeStates.update { it + (id to ContainerRuntime.RUNNING) }
                // 期望运行（restart 策略判据）：手动/compose/autoStart 启动一视同仁；
                // 同时持久化到 DB，进程被杀重启后 autoStartAll 据此恢复（docker 语义）
                desiredRunning[id] = System.currentTimeMillis()
                dao.upsertContainer(container.copy(status = ContainerStatus.RUNNING, desiredRunning = true))
                emitEvent("start", container)
                AppLogger.i(TAG, "start id=$id name=${container.name} pid=$pid abi=$hostAbi cmd=$command")
                pid
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "start id=$id failed", e)
            throw e
        }
    }

    /** 停止容器（先子后父），并关闭该容器的终端会话（同 docker stop 连带 exec 会话）。 */
    suspend fun stop(id: String) = withContext(dispatchers.io) {
        AppLogger.i(TAG, "stop id=$id requested")
        lifecycleMutex.withLock {
            // 先清期望状态再杀进程：主进程退出回调晚于本方法时，restart 策略不得拉起。
            // DB 的 desiredRunning 一并清除：用户显式停止是 unless-stopped 容器
            // 进程重启后「不恢复」的判据（docker 语义）
            desiredRunning.remove(id)
            restartAttempts.remove(id)
            manager.stop(id)
            terminals.closeForContainer(id)
            _runtimeStates.update { it + (id to ContainerRuntime.STOPPED) }
            dao.findContainer(id)?.let {
                dao.upsertContainer(it.copy(status = ContainerStatus.STOPPED, desiredRunning = false))
                emitEvent("stop", it)
                AppLogger.i(TAG, "stop id=$id name=${it.name} done")
            }
        }
        Unit
    }

    /** 删除容器（运行中先停止；仅删库存，rootfs 属于镜像不动）。 */
    suspend fun remove(id: String) = withContext(dispatchers.io) {
        AppLogger.i(TAG, "remove id=$id requested")
        lifecycleMutex.withLock {
            desiredRunning.remove(id)
            restartAttempts.remove(id)
            if (manager.isRunning(id)) manager.stop(id)
            terminals.closeForContainer(id)
            dao.findContainer(id)?.let { emitEvent("destroy", it) }
            dao.deleteContainer(id)
            _runtimeStates.update { it - id }
        }
        AppLogger.i(TAG, "remove id=$id done")
    }

    /** 重命名容器（M9 阶段二：docker rename 映射库存 name 更新；dockerId 不变）。 */
    suspend fun rename(id: String, newName: String): ContainerEntity = withContext(dispatchers.io) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            throw EngineException(EngineErrorCode.START_FAILED, "container name is blank")
        }
        val entity = dao.findContainer(id)
            ?: throw EngineException(EngineErrorCode.START_FAILED, "container $id not found")
        if (dao.observeContainers().first().any { it.name == trimmed && it.id != id }) {
            throw EngineException(EngineErrorCode.START_FAILED, "name '$trimmed' already in use")
        }
        val updated = entity.copy(name = trimmed)
        dao.upsertContainer(updated)
        updated
    }

    /** 读取当前日志（IO 线程，审查 P1-10：文件 IO 不进调用方线程）。 */
    suspend fun readLog(id: String): List<String> = withContext(dispatchers.io) {
        manager.readLog(id)
    }

    /** 日志增量读取（M8 follow 流地基）。 */
    suspend fun readLogFrom(id: String, byteOffset: Long): ContainerManager.LogChunk =
        withContext(dispatchers.io) { manager.readLogFrom(id, byteOffset) }

    /** 容器是否在本进程运行表中（内存态，方案 §3.4）。 */
    fun isRunning(id: String): Boolean = manager.isRunning(id)

    /** 运行中容器的宿主 pid（Docker /containers/{id}/top 用）。 */
    fun pidOf(id: String): Int? = manager.pidOf(id)

    // ------------------------------------------------------------ Docker 兼容（M9）

    /**
     * 取容器（已确保 dockerId）：Docker API 引用统一走此入口，
     * 返回的 entity.dockerId 非 null，可直接当 64 hex Id 用。
     */
    suspend fun resolveForDocker(ref: String): ContainerEntity? = withContext(dispatchers.io) {
        val entity = resolveContainerRef(ref) ?: return@withContext null
        ensureDockerId(entity)
    }

    /**
     * 三级解析（m8_m9 §4.2-1）：内部 id 精确 → dockerId 精确/前缀（≥4 位）→ 名称。
     */
    suspend fun resolveContainerRef(ref: String): ContainerEntity? = withContext(dispatchers.io) {
        dao.findContainer(ref)?.let { return@withContext it }
        val all = dao.observeContainers().first()
        all.firstOrNull { it.dockerId == ref }?.let { return@withContext it }
        all.firstOrNull { it.name == ref }?.let { return@withContext it }
        if (ref.length >= 4) all.firstOrNull { it.dockerId?.startsWith(ref) == true } else null
    }

    /** 惰性生成 64 hex dockerId 并落库（DB v3 列；旧记录首次访问时补齐）。 */
    private suspend fun ensureDockerId(entity: ContainerEntity): ContainerEntity {
        entity.dockerId?.let { return entity }
        val updated = entity.copy(dockerId = randomDockerId())
        dao.upsertContainer(updated)
        return updated
    }

    private fun randomDockerId(): String {
        val bytes = java.security.SecureRandom().let { rng ->
            ByteArray(32).also { rng.nextBytes(it) }
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ compose 编排支撑（M7）

    /** 按「服务身份」（项目 + 服务名）查容器，供 compose up 幂等复用。 */
    suspend fun findByService(projectId: String, serviceName: String): ContainerEntity? =
        withContext(dispatchers.io) { dao.findContainerByService(projectId, serviceName) }

    suspend fun containersOfProject(projectId: String): List<ContainerEntity> =
        withContext(dispatchers.io) { dao.findContainersByProject(projectId) }

    /**
     * 创建或复用 compose 服务容器：同名服务重复 up 不会产生重复容器（身份是 projectId + serviceName）。
     * 命令与环境变量写库，[start] 时生效。
     *
     * [httpPorts]：正文声明的容器端口，随**新建**容器写入 portsJson —— 容器页「浏览器」
     * 与编排详情的直达按钮开箱即用；已存在的容器不回写，避免覆盖用户手工增删过的端口。
     */
    suspend fun upsertServiceContainer(
        projectId: String,
        projectName: String,
        serviceName: String,
        imageRef: String,
        command: List<String>,
        environment: Map<String, String>,
        httpPorts: List<Int> = emptyList(),
        /** named volume binds（`host:container[:ro]` 编码），随容器持久化到 mountsJson。 */
        volumeBinds: List<String> = emptyList(),
        /** compose `restart` 策略（RestartPolicies 取值；null = 未声明），随容器持久化。 */
        restartPolicy: String? = null,
    ): ContainerEntity = withContext(dispatchers.io) {
        val containerName = ComposeNaming.containerName(projectName, serviceName)
        val existing = dao.findContainerByService(projectId, serviceName)
        val base = existing ?: ContainerEntity(
            id = UUID.randomUUID().toString(),
            name = containerName,
            imageRef = imageRef,
            status = ContainerStatus.CREATED,
            createdAt = System.currentTimeMillis(),
            portsJson = ContainerPayloads.encodeIntList(httpPorts.distinct().sorted()),
        )
        val entity = base.copy(
            name = containerName,
            imageRef = imageRef,
            projectId = projectId,
            serviceName = serviceName,
            cmdJson = ContainerPayloads.encodeList(command),
            envJson = ContainerPayloads.encodeMap(environment),
            // compose up 每次都按当前 YAML 刷新；非 volume 场景写回空数组不破坏语义
            mountsJson = ContainerPayloads.encodeList(volumeBinds),
            // restart 策略同样按当前 YAML 刷新（用户从 compose 删掉 restart 即回落 no）
            restartPolicy = restartPolicy,
        )
        dao.upsertContainer(entity)
        if (existing == null) emitEvent("create", entity)
        entity
    }

    // ------------------------------------------------------------ 终端（独立 proot shell 会话 + PTY）

    /** 当前终端会话数（诊断/展示用）。 */
    fun terminalSessionCount(): Int = terminals.count

    /** 取会话（WS 重连回放等场景）。 */
    fun terminal(sessionId: String): TerminalSession? = terminals.get(sessionId)

    /** 关闭某容器的全部终端会话。 */
    fun closeTerminals(containerId: String) = terminals.closeForContainer(containerId)

    /**
     * 打开容器终端：新起一个 proot 进程挂同一 rootfs（独立于容器主进程），
     * 经 libpty 赋予真 PTY——回显/颜色/Ctrl-C 与动态 resize 全部可用。
     * 不要求容器处于运行态（rootfs 存在即可进 shell，语义上弱于 docker exec 但更实用）。
     * 环境注入与 [start] 同级：镜像 ENV + 用户 env + 引擎运行环境，另补 TERM。
     */
    suspend fun openTerminal(id: String, cols: Int = 80, rows: Int = 24): TerminalSession =
        withContext(dispatchers.io) {
            val container = dao.findContainer(id)
                ?: throw EngineException(EngineErrorCode.START_FAILED, "container $id not found")
            val image = dao.findImage(container.imageRef)
                ?: throw EngineException(EngineErrorCode.START_FAILED, "image ${container.imageRef} missing")
            val rootfs = File(image.rootfsPath)
            if (!rootfs.isDirectory) {
                throw EngineException(EngineErrorCode.START_FAILED, "rootfs missing: ${rootfs.path}")
            }
            if (!ProotRuntime.hasShell(rootfs)) {
                throw EngineException(EngineErrorCode.START_FAILED, "image has no /bin/sh: ${container.imageRef}")
            }

            // 交互 shell 选择：优先 /bin/bash。Debian 系的 /bin/sh 是 dash——编译时无
            // 行编辑库，交互模式也不切 raw mode，tty 停在 canonical，方向键会被 tty
            // 回显成 ^[[A 字面量（真实 docker exec -it sh 同样如此）。bash 自带
            // readline，会接管行编辑并设 raw，方向键/历史/Ctrl-C 才有意义。
            // Alpine 的 busybox ash 自带行编辑，无 bash 时 /bin/sh 也够用。
            val shellCommand = if (hasRootfsFile(rootfs, "bin/bash")) {
                listOf("/bin/bash", "-l")
            } else {
                listOf("/bin/sh", "-l")
            }

            // 与 start() 相同的绑定：DNS 只读 resolv.conf + named volumes（终端能看到 volume 数据）
            val dns = settings.dns.first()
            val bindsDir = File(engineDir, "binds").apply { mkdirs() }
            val resolv = File(bindsDir, "resolv.conf").apply { writeText("nameserver $dns\n") }
            val volumeBinds = NamedVolumes.decodeMounts(ContainerPayloads.decodeList(container.mountsJson))

            val spec = runtime.launch(
                rootfs = rootfs,
                workdir = DEFAULT_WORKDIR,
                command = shellCommand,
                userBinds = listOf(
                    cn.yzapp.androidcontainer.core.engine.proot.BindMount(
                        hostPath = resolv,
                        containerPath = "/etc/resolv.conf",
                        readOnly = true,
                    ),
                ) + volumeBinds,
            )
            val imageEnv = ImageDefaultEntry.envFromConfig(File(rootfs.parentFile, "config"))
            val environment = imageEnv +
                ContainerPayloads.environmentOf(container) +
                spec.environment +
                // 终端程序按 TERM 选择颜色/能力；xterm-256color 与前端 xterm.js 对应
                mapOf("TERM" to "xterm-256color")
            terminals.open(id, spec.argv, environment, cols, rows, dispatchers.io)
        }

    /** exec：向运行中容器的 sh stdin 写命令；输出进运行日志。 */
    fun exec(id: String, command: String) = manager.exec(id, command)

    /**
     * rootfs 内文件存在性探测（终端选 shell 用）。/bin/sh、/bin/bash 在镜像内常为
     * 绝对路径符号链接（Alpine: sh -> /bin/busybox），宿主视角解析不到链接目标
     * （isFile=false），须把目标映射回 rootfs 内再判断（与 ProotRuntime.hasShell 同理）。
     */
    private fun hasRootfsFile(rootfs: File, relativePath: String): Boolean {
        val file = File(rootfs, relativePath)
        if (file.isFile) return true
        return runCatching {
            val target = java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
            val resolved = if (target.startsWith("/")) {
                File(rootfs, target.trimStart('/'))
            } else {
                File(file.parentFile, target)
            }
            resolved.isFile
        }.getOrDefault(false)
    }

    /** 记录容器对外 HTTP 端口（浏览器一键打开用，存 portsJson 既有列）。 */
    suspend fun setHttpPorts(id: String, ports: List<Int>) = withContext(dispatchers.io) {
        val container = dao.findContainer(id)
            ?: throw EngineException(EngineErrorCode.START_FAILED, "container $id not found")
        dao.upsertContainer(container.copy(portsJson = ContainerPayloads.encodeIntList(ports)))
    }

    /**
     * App 启动时拉起自启容器（方案 §5 自启策略，设置开关控制）。
     *
     * 两类候选（满足其一）：
     * - `autoStart` 标志（创建容器时勾选「随 App 启动」）；
     * - 持久化的 `desiredRunning` 且 restart 策略 ∈ [RestartPolicies.AUTO_RESTART] ——
     *   对齐 docker：daemon 重启后恢复「restart=always/unless-stopped 且未被显式停止」
     *   的容器。App 进程被杀时 proot 全灭但 desiredRunning 保持 true（显式 stop 才清除），
     *   重开 App 即恢复现场。
     */
    suspend fun autoStartAll() = withContext(dispatchers.io) {
        val enabled = settings.autostartEnabled.first()
        if (!enabled) return@withContext
        val candidates = dao.observeContainers().first().filter {
            it.autoStart || (it.desiredRunning && it.restartPolicy in RestartPolicies.AUTO_RESTART)
        }
        if (candidates.isNotEmpty()) {
            AppLogger.i(TAG, "autoStartAll candidates: ${candidates.map { it.name }}")
        }
        candidates.forEach {
            try {
                start(it.id)
            } catch (e: Exception) {
                // 单个容器自启失败不影响其余；记录原因，不再静默吞掉（排查自启问题全靠它）
                AppLogger.w(TAG, "autostart id=${it.id} name=${it.name} failed: ${e.message}")
            }
        }
    }

    /**
     * 容器默认入口：优先镜像 config 的 ENTRYPOINT/CMD（拉取时落盘于 rootfs 同级 config，
     * docker 语义）；缺失（旧拉取或未声明）时回落：有 /bin/sh 用 sh（无 shell 镜像回退由
     * ProotRuntime.hasShell 判定）。
     */
    private fun defaultCommand(rootfs: File): List<String> {
        val fromImage = ImageDefaultEntry.argvFromConfig(File(rootfs.parentFile, "config"))
        if (fromImage.isNotEmpty()) return fromImage
        return if (ProotRuntime.hasShell(rootfs)) listOf("/bin/sh") else listOf("/init")
    }

    /**
     * 状态校正（M6）：App 进程重启后运行表为空，DB 中残留的 RUNNING 是僵尸状态，
     * 启动时统一校正为 STOPPED。
     */
    suspend fun reconcile() = withContext(dispatchers.io) {
        dao.observeContainers().first().forEach { container ->
            if (container.status == ContainerStatus.RUNNING && !manager.isRunning(container.id)) {
                dao.upsertContainer(container.copy(status = ContainerStatus.STOPPED))
            }
        }
        _runtimeStates.update { it.filterValues { state -> state == ContainerRuntime.RUNNING } }
        // 进程重启后期望状态与运行表一样不复存在
        desiredRunning.clear()
        restartAttempts.clear()
    }

    /**
     * restart 策略自动拉起（docker 语义子集）：
     * - 仅当用户没有显式停止（[desiredRunning] 仍持有该 id）且策略属于
     *   [RestartPolicies.AUTO_RESTART]（always / unless-stopped / on-failure）时拉起；
     * - 退避：1s 起倍增、封顶 [RESTART_BACKOFF_MAX_MS]，进程稳定运行超过
     *   [RESTART_STABLE_MS] 后计数清零（对齐 docker 的重置语义，移动端取值更保守）；
     * - 连续 [MAX_RESTART_ATTEMPTS] 次失败后放弃并清期望状态（桌面 docker 会无限重试，
     *   移动端持续拉起一个必崩容器空耗电，放弃后 UI 显示已停止，用户可手动再启）；
     * - start 抛异常（rootfs 丢失等持久性错误）同样放弃。
     */
    private suspend fun maybeAutoRestart(id: String, entity: ContainerEntity?) {
        val policy = entity?.restartPolicy
        if (policy == null || policy !in RestartPolicies.AUTO_RESTART) return
        val startedAt = desiredRunning[id] ?: return // 用户已显式停止 / 容器已删除

        val stable = System.currentTimeMillis() - startedAt > RESTART_STABLE_MS
        val attempts = if (stable) 0 else (restartAttempts[id] ?: 0)
        if (attempts >= MAX_RESTART_ATTEMPTS) {
            AppLogger.w(TAG, "auto-restart id=$id gave up after $attempts attempts; clearing desired state")
            desiredRunning.remove(id)
            restartAttempts.remove(id)
            // 放弃 = 该容器必崩/不可恢复，进程重启后也不得再自动拉起
            dao.findContainer(id)?.let {
                if (it.desiredRunning) dao.upsertContainer(it.copy(desiredRunning = false))
            }
            return
        }

        val delayMs = (RESTART_BACKOFF_BASE_MS shl attempts).coerceAtMost(RESTART_BACKOFF_MAX_MS)
        restartAttempts[id] = attempts + 1
        AppLogger.i(TAG, "auto-restart id=$id policy=$policy attempt=${attempts + 1} in ${delayMs}ms")
        delay(delayMs)

        // 退避期间被 stop / remove / down → 放弃
        if (!desiredRunning.containsKey(id)) return
        try {
            start(id)
        } catch (e: Exception) {
            AppLogger.w(TAG, "auto-restart id=$id failed; giving up", e)
            desiredRunning.remove(id)
            restartAttempts.remove(id)
            dao.findContainer(id)?.let {
                if (it.desiredRunning) dao.upsertContainer(it.copy(desiredRunning = false))
            }
        }
    }

    private companion object {
        private const val TAG = "Container"
        const val DEFAULT_WORKDIR = "/"

        /** 自动重启退避：1s 起倍增（1s/2s/4s/…），封顶 30s。 */
        const val RESTART_BACKOFF_BASE_MS = 1000L
        const val RESTART_BACKOFF_MAX_MS = 30_000L

        /** 进程稳定运行该时长后，连续重启计数清零（docker 为 10s，移动端取 60s 更保守）。 */
        const val RESTART_STABLE_MS = 60_000L

        /** 连续自动重启上限（防 crash loop 空耗电；docker 无上限，移动端取舍）。 */
        const val MAX_RESTART_ATTEMPTS = 5
    }
}
