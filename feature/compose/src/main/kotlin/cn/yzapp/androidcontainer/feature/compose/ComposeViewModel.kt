package cn.yzapp.androidcontainer.feature.compose

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.data.ComposeProjectState
import cn.yzapp.androidcontainer.core.data.ContainerRuntime
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.data.OpsErrors
import cn.yzapp.androidcontainer.core.data.composeProjectState
import cn.yzapp.androidcontainer.core.data.db.ComposeProjectEntity
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.engine.compose.ComposeIssue
import cn.yzapp.androidcontainer.core.engine.compose.ComposeParseException
import cn.yzapp.androidcontainer.core.engine.compose.ComposeSamples
import cn.yzapp.androidcontainer.core.engine.compose.containerPortValue
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.PullProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 项目卡片数据（服务数来自 YAML 即时解析，方案 §3.1「解析产物不落库」）。 */
data class ComposeProjectUi(
    val id: String,
    val name: String,
    val serviceCount: Int,
    val state: ComposeProjectState,
)

/** 详情页单个服务的运行视图。 */
data class ComposeServiceUi(
    val name: String,
    val image: String,
    val ports: List<String>,
    val dependsOn: List<String>,
    val containerId: String?,
    val running: Boolean,
    /** 正文声明的容器端口（proot 共享网络栈，浏览器 127.0.0.1 直达）。 */
    val webPorts: List<Int> = emptyList(),
)

data class ComposeDetailUi(
    val projectId: String,
    val projectName: String,
    val services: List<ComposeServiceUi>,
    val issues: List<ComposeIssue>,
    val parseError: String?,
    val busy: Boolean,
)

/** YAML 编辑器状态：解析结果实时回显（服务清单 / 兼容性提示 / 错误行号）。 */
data class ComposeEditorUi(
    val visible: Boolean = false,
    val projectId: String? = null,
    val name: String = "",
    val yaml: String = "",
    val error: String? = null,
    val errorLine: Int? = null,
    val serviceNames: List<String> = emptyList(),
    val issues: List<ComposeIssue> = emptyList(),
    val busy: Boolean = false,
)

class ComposeViewModel(app: Application) : AndroidViewModel(app) {

    private val compose = DataGraph.composeRepository
    private val containers = DataGraph.containerRepository

    private val projects =
        compose.observeProjects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allContainers =
        compose.observeContainers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editor = MutableStateFlow(ComposeEditorUi())
    val editor: StateFlow<ComposeEditorUi> = _editor.asStateFlow()

    private val _openedProjectId = MutableStateFlow<String?>(null)
    val openedProjectId: StateFlow<String?> = _openedProjectId.asStateFlow()

    /** 正在执行 up/down 的项目 id：进程级（跨列表页与详情页），任务不再绑页面生命周期。 */
    val busyProjectId: StateFlow<String?> = ComposeOps.busyProjectId

    /** 镜像拉取进度（key = imageRef）：up 时缺镜像自动拉取，进度展示在列表页与详情页。 */
    val pullStates: StateFlow<Map<String, PullProgress>> =
        DataGraph.imageRepository.pullStates

    /** 最近一次 up 的启动提示：进程级，列表页与详情页看到同一条。 */
    val startupIssues: StateFlow<List<ComposeIssue>> = ComposeOps.startupIssues

    /** 展开日志的容器 id → 行列表（与容器页同一套「输入重定向终端」轮询方案）。 */
    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    /** 操作失败提示：写进程级通道，长任务在页面销毁后失败也不会静默丢失（审查 P1-11）。 */
    val error: StateFlow<String?> = OpsErrors.message

    /** 卡片列表：项目 + 容器运行态聚合；up 执行中（含镜像拉取）状态临时覆盖为 STARTING。 */
    val projectCards: StateFlow<List<ComposeProjectUi>> =
        combine(projects, allContainers, containers.runtimeStates, ComposeOps.busyProjectId, ComposeOps.busyOp) {
                projectList, containerList, runtime, busyId, busyOp,
            ->
            projectList.map { project ->
                val spec = compose.parseOrNull(project.yamlContent)
                val owned = containerList.filter { it.projectId == project.id }
                val derived = composeProjectState(
                    spec?.services?.size ?: owned.size,
                    owned,
                    runtime,
                )
                ComposeProjectUi(
                    id = project.id,
                    name = project.name,
                    serviceCount = spec?.services?.size ?: owned.size,
                    state = if (busyId == project.id && busyOp == BusyOp.UP) {
                        ComposeProjectState.STARTING
                    } else {
                        derived
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val detail: StateFlow<ComposeDetailUi?> =
        combine(
            _openedProjectId,
            projects,
            allContainers,
            containers.runtimeStates,
            ComposeOps.busyProjectId,
        ) { projectId, projectList, containerList, runtime, busyId ->
            val project = projectList.firstOrNull { it.id == projectId } ?: return@combine null
            buildDetail(project, containerList, runtime, busyProjectId = busyId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 审查 P1-11：无常驻 while(true) 轮询——改为每个展开的日志一个 1Hz job
    // （见 toggleLogs），全部收起时零唤醒。
    // 模板「使用此模板」的草稿也不再在这里 collect：本 VM 已随编排页条目存活/销毁，
    // 改由编辑器页在自己初始化时一次性消费（见 openEditor）。

    // ---------------------------------------------------------------- 编辑器

    /**
     * 按导航参数准备编辑草稿：`projectId == null` → 新建，否则编辑既有项目。
     *
     * 原实现由列表页/详情页先调用 `openNewProject()` / `openEditor(id)` 把草稿写进
     * ViewModel，编辑器页读**同一个** Activity 级实例；现在每个导航条目有自己的
     * ViewModel（entry 级 ViewModelStore），改为本页按 `projectId` 自行初始化（审查 P1-11）。
     * 顺带修掉了进程恢复场景：`projectId` 在 NavKey 里，重建后能重新取出草稿，
     * 不再出现「恢复后编辑器空白」。
     *
     * 模板草稿仍走 [ComposeDraftBus]（YAML 不进 NavKey，也会进序列化/日志），在此消费一次。
     */
    fun openEditor(projectId: String?) {
        viewModelScope.launch {
            val project = projectId?.let { compose.findProject(it) }
            val draft = if (projectId == null) ComposeDraftBus.consume() else null
            _editor.value = reparse(
                when {
                    project != null -> ComposeEditorUi(
                        visible = true,
                        projectId = project.id,
                        name = project.name,
                        yaml = project.yamlContent,
                    )
                    // 入口到读取之间项目被删（窗口极小）：兜底为新建草稿，
                    // 不留一个不可见的编辑器状态把页面卡住
                    draft != null -> ComposeEditorUi(
                        visible = true,
                        projectId = null,
                        name = draft.name,
                        yaml = draft.yaml,
                    )
                    else -> ComposeEditorUi(
                        visible = true,
                        projectId = null,
                        name = ComposeSamples.DEFAULT_PROJECT_NAME,
                        yaml = ComposeSamples.REDIS_WEB,
                    )
                },
            )
        }
    }

    fun closeEditor() {
        _editor.value = ComposeEditorUi()
    }

    fun onNameChange(value: String) {
        _editor.value = _editor.value.copy(name = value)
    }

    fun onYamlChange(value: String) {
        _editor.value = reparse(_editor.value.copy(yaml = value))
    }

    fun loadSample() {
        _editor.value = reparse(_editor.value.copy(yaml = ComposeSamples.REDIS_WEB))
    }

    /** SAF 导入 .yaml/.yml：只读取文本内容，不持有 uri 权限。 */
    fun importYaml(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }
                if (text.isNullOrBlank()) {
                    OpsErrors.report(
                        getApplication<Application>().getString(R.string.compose_import_failed_empty),
                    )
                    return@launch
                }
                val name = _editor.value.name.ifBlank {
                    uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                        ?: ComposeSamples.DEFAULT_PROJECT_NAME
                }
                _editor.value = reparse(_editor.value.copy(name = name, yaml = text))
            } catch (e: Exception) {
                OpsErrors.report(
                    getApplication<Application>()
                        .getString(R.string.compose_import_failed, e.message ?: ""),
                )
            }
        }
    }

    fun saveProject() {
        val state = _editor.value
        val parsed = compose.parseOrNull(state.yaml)
        if (parsed == null) {
            _editor.value = reparse(state)
            return
        }
        viewModelScope.launch {
            _editor.value = state.copy(busy = true)
            try {
                if (state.projectId == null) {
                    compose.createProject(state.name, state.yaml)
                } else {
                    compose.updateProject(state.projectId, state.name, state.yaml)
                }
                _editor.value = ComposeEditorUi()
            } catch (e: EngineException) {
                _editor.value = state.copy(busy = false, error = e.message)
            }
        }
    }

    // ---------------------------------------------------------------- 生命周期操作
    //
    // 全部挂 DataGraph.appScope：拉镜像 / 起容器是分钟级任务，而本 VM 现在随导航条目
    // 出栈销毁（切 tab 即销毁），挂在 viewModelScope 上会被静默取消（审查 P1-11）。
    // 「进行中」的事实写进程级 ComposeOps，失败写 OpsErrors，两者都不依赖页面存活。

    fun up(projectId: String) {
        ComposeOps.beginUp(projectId)
        DataGraph.appScope.launch {
            try {
                val report = compose.up(projectId)
                ComposeOps.setStartupIssues(report.issues)
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            } finally {
                ComposeOps.finish()
            }
        }
    }

    fun down(projectId: String, removeContainers: Boolean) {
        ComposeOps.beginDown(projectId)
        DataGraph.appScope.launch {
            try {
                compose.down(projectId, removeContainers)
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            } finally {
                ComposeOps.finish()
            }
        }
    }

    fun deleteProject(projectId: String) {
        DataGraph.appScope.launch {
            try {
                compose.deleteProject(projectId, removeContainers = true)
                // 删掉的正是当前详情页的项目 → 详情变 null，页面自行退回列表
                if (_openedProjectId.value == projectId) _openedProjectId.value = null
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            }
        }
    }

    fun openDetail(projectId: String) {
        _openedProjectId.value = projectId
        ComposeOps.clearStartupIssues()
    }

    fun closeDetail() {
        _openedProjectId.value = null
        ComposeOps.clearStartupIssues()
        logPollJobs.values.forEach { it.cancel() }
        logPollJobs.clear()
        _logs.value = emptyMap()
    }

    fun startService(containerId: String) {
        DataGraph.appScope.launch {
            try {
                containers.start(containerId)
                // 容器进入 RUNNING → 拉起保活前台服务，防止退后台被系统查杀
                getApplication<Application>().startForegroundService(
                    android.content.Intent(
                        cn.yzapp.androidcontainer.core.common.ContainerRuntimeActions.ACTION_START,
                    ).setPackage(getApplication<Application>().packageName),
                )
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            }
        }
    }

    fun stopService(containerId: String) {
        DataGraph.appScope.launch {
            try {
                containers.stop(containerId)
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            }
        }
    }

    /** 用系统浏览器打开服务端口（proot 与手机共享网络栈，127.0.0.1 直达）。 */
    fun openServicePage(port: Int) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("http://127.0.0.1:$port"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { OpsErrors.report(it.message) }
    }

    /** 展开日志的轮询 job（id → job，审查 P1-11：有展开才有轮询，关闭即取消）。 */
    private val logPollJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun toggleLogs(containerId: String) {
        val current = _logs.value
        if (current.containsKey(containerId)) {
            logPollJobs.remove(containerId)?.cancel()
            _logs.value = current - containerId
        } else {
            viewModelScope.launch {
                _logs.value = _logs.value + (containerId to containers.readLog(containerId))
            }
            logPollJobs[containerId] = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    if (!_logs.value.containsKey(containerId)) break
                    _logs.value = _logs.value + (containerId to containers.readLog(containerId))
                }
            }
        }
    }

    fun dismissError() {
        OpsErrors.clear()
    }

    // ---------------------------------------------------------------- 内部

    private fun buildDetail(
        project: ComposeProjectEntity,
        containerList: List<ContainerEntity>,
        runtime: Map<String, ContainerRuntime>,
        busyProjectId: String?,
    ): ComposeDetailUi {
        val spec = compose.parseOrNull(project.yamlContent)
        val owned = containerList.filter { it.projectId == project.id }
        val services = spec?.services?.map { service ->
            val container = owned.firstOrNull { it.serviceName == service.name }
            ComposeServiceUi(
                name = service.name,
                image = service.image,
                ports = service.ports,
                dependsOn = service.dependsOn,
                containerId = container?.id,
                running = container != null && runtime[container.id] == ContainerRuntime.RUNNING,
                webPorts = service.ports.mapNotNull { it.containerPortValue() }.distinct(),
            )
        }.orEmpty()
        return ComposeDetailUi(
            projectId = project.id,
            projectName = project.name,
            services = services,
            issues = spec?.issues.orEmpty(),
            parseError = if (spec == null) parseErrorText(project.yamlContent) else null,
            busy = busyProjectId == project.id,
        )
    }

    private fun reparse(state: ComposeEditorUi): ComposeEditorUi = try {
        val spec = compose.parse(state.yaml)
        state.copy(
            error = null,
            errorLine = null,
            serviceNames = spec.serviceNames,
            issues = spec.issues,
        )
    } catch (e: ComposeParseException) {
        state.copy(error = e.message, errorLine = e.line, serviceNames = emptyList(), issues = emptyList())
    }

    private fun parseErrorText(yaml: String): String = try {
        compose.parse(yaml)
        getApplication<Application>().getString(R.string.compose_parse_unknown)
    } catch (e: ComposeParseException) {
        e.displayMessage
    }
}
