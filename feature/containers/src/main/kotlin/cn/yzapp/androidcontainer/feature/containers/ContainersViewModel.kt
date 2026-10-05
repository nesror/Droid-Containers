package cn.yzapp.androidcontainer.feature.containers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.common.DockerRunParser
import cn.yzapp.androidcontainer.core.data.ContainerPayloads
import cn.yzapp.androidcontainer.core.data.ContainerRepository
import cn.yzapp.androidcontainer.core.data.ContainerRuntime
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.data.OpsErrors
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.PullProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class CreationUiState(
    val expanded: Boolean = false,
    val name: String = "",
    val imageRef: String = "alpine:3.20",
    val autoStart: Boolean = false,
    val error: String? = null,
    /** docker run 命令输入框内容。 */
    val dockerCommand: String = "",
    /** 最近一次解析 docker 命令的结果（null = 尚未解析）。 */
    val dockerParse: DockerParseResult? = null,
)

/** docker 命令解析结果：ok=false 表示无法识别；unsupported 为被忽略的选项。 */
data class DockerParseResult(
    val ok: Boolean,
    val image: String? = null,
    val name: String? = null,
    val detached: Boolean = false,
    val unsupported: List<String> = emptyList(),
)

/** 容器网页端口编辑对话框状态（一键用系统浏览器打开 H5 页面）。 */
data class PortEditorUiState(
    val containerId: String,
    val ports: List<Int>,
    val input: String = "",
)

data class ContainersUiState(
    val containers: List<ContainerEntity> = emptyList(),
    val runtimeStates: Map<String, ContainerRuntime> = emptyMap(),
    val creation: CreationUiState = CreationUiState(),
    /** 展开日志的容器 id -> 行列表。 */
    val logs: Map<String, List<String>> = emptyMap(),
)

class ContainersViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DataGraph.containerRepository

    val containers =
        repository.observeContainers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runtimeStates: StateFlow<Map<String, ContainerRuntime>> =
        repository.runtimeStates

    /** 镜像拉取进度（key = imageRef）：创建容器缺镜像自动拉取时在表单内展示。 */
    val pullStates: StateFlow<Map<String, PullProgress>> =
        DataGraph.imageRepository.pullStates

    private val _creation = MutableStateFlow(CreationUiState())
    val creation: StateFlow<CreationUiState> = _creation.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    /** 操作失败提示：进程级通道，长任务在页面销毁后失败也不会静默丢失（审查 P1-11）。 */
    val error: StateFlow<String?> = OpsErrors.message

    /** 创建进行中（进程级）：页面重建后仍置灰按钮并显示拉取进度。 */
    val creationWorking: StateFlow<Boolean> = ContainerCreationOps.working

    private val _portEditor = MutableStateFlow<PortEditorUiState?>(null)
    val portEditor: StateFlow<PortEditorUiState?> = _portEditor.asStateFlow()

    fun dismissError() {
        OpsErrors.clear()
    }

    fun toggleCreation(expanded: Boolean) {
        _creation.value = _creation.value.copy(expanded = expanded, error = null)
    }

    fun onNameChange(value: String) {
        _creation.value = _creation.value.copy(name = value)
    }

    fun onImageRefChange(value: String) {
        _creation.value = _creation.value.copy(imageRef = value)
    }

    fun onAutoStartChange(value: Boolean) {
        _creation.value = _creation.value.copy(autoStart = value)
    }

    fun onDockerCommandChange(value: String) {
        _creation.value = _creation.value.copy(dockerCommand = value, dockerParse = null)
    }

    /** 解析 docker run 命令并填充表单（名称/镜像/自启），不支持项通过结果回显。 */
    fun applyDockerCommand() {
        val state = _creation.value
        val spec = DockerRunParser.parse(state.dockerCommand)
        _creation.value = if (spec == null) {
            state.copy(dockerParse = DockerParseResult(ok = false))
        } else {
            state.copy(
                name = spec.name ?: state.name,
                imageRef = spec.image ?: state.imageRef,
                autoStart = spec.detached || state.autoStart,
                dockerParse = DockerParseResult(
                    ok = true,
                    image = spec.image,
                    name = spec.name,
                    detached = spec.detached,
                    unsupported = spec.unsupportedOptions,
                ),
            )
        }
    }

    /**
     * 创建容器。任务挂应用级作用域（缺镜像时会自动拉取，分钟级）：页面销毁不打断；
     * 「进行中」走进程级 [ContainerCreationOps]，页面重建后仍置灰按钮、仍显示进度，
     * 避免切页回来重复提交同一个容器（审查 P1-11）。
     */
    fun create() {
        val state = _creation.value
        if (!ContainerCreationOps.tryBegin()) return
        _creation.value = state.copy(error = null)
        DataGraph.appScope.launch {
            try {
                repository.create(
                    name = state.name,
                    imageRef = state.imageRef.trim(),
                    entryCommand = emptyList(),
                    autoStart = state.autoStart,
                )
                _creation.value = CreationUiState(expanded = false)
            } catch (e: EngineException) {
                _creation.value = _creation.value.copy(error = e.message)
            } finally {
                ContainerCreationOps.end()
            }
        }
    }

    /** exec：向运行中容器的 sh stdin 写命令（输出进运行日志）。 */
    fun exec(id: String, command: String) {
        DataGraph.appScope.launch {
            try {
                repository.exec(id, command.trim())
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            }
        }
    }

    // ---- 浏览器一键打开容器 H5 页面（proot 进程直接监听本机端口，127.0.0.1 直达）----

    fun showPortEditor(id: String) {
        viewModelScope.launch {
            // 容器可能已被删除：原 `first{}` 会永久挂起，端口对话框再也不出现（审查 P1-20）
            val container = withTimeoutOrNull(5_000L) {
                repository.observeContainers().first { list -> list.any { it.id == id } }
                    .firstOrNull { it.id == id }
            } ?: return@launch
            _portEditor.value = PortEditorUiState(
                containerId = id,
                ports = ContainerPayloads.httpPortsOf(container),
            )
        }
    }

    fun dismissPortEditor() {
        _portEditor.value = null
    }

    fun onPortInputChange(value: String) {
        _portEditor.value = _portEditor.value?.copy(input = value.filter { it.isDigit() }.take(5))
    }

    fun removePort(port: Int) {
        val editor = _portEditor.value ?: return
        val remaining = editor.ports - port
        viewModelScope.launch {
            try {
                repository.setHttpPorts(editor.containerId, remaining)
            } catch (_: EngineException) {
            }
            _portEditor.value = editor.copy(ports = remaining)
        }
    }

    /** 保存输入的端口（去重）并用系统浏览器打开 http://127.0.0.1:port。 */
    fun rememberAndOpenPort() {
        val editor = _portEditor.value ?: return
        val port = editor.input.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        viewModelScope.launch {
            try {
                repository.setHttpPorts(editor.containerId, (editor.ports + port).distinct().sorted())
            } catch (_: EngineException) {
            }
            openBrowser(port)
            _portEditor.value = null
        }
    }

    /** 直接打开已记录端口。 */
    fun openSavedPort(port: Int) {
        openBrowser(port)
        _portEditor.value = null
    }

    private fun openBrowser(port: Int) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("http://127.0.0.1:$port"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { OpsErrors.report(it.message) }
    }

    init {
        // 审查 P1-11：无常驻 while(true) 轮询——改为每个展开的日志一个 1Hz job
        // （见 toggleLogs），全部收起时零唤醒
    }

    // 启停/删除挂应用级作用域：启动可能要先拉镜像并解压层，分钟级；页面销毁不打断（审查 P1-11）

    fun start(id: String) {
        DataGraph.appScope.launch {
            try {
                repository.start(id)
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

    fun stop(id: String) {
        DataGraph.appScope.launch {
            try {
                repository.stop(id)
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            }
        }
    }

    fun remove(id: String) {
        DataGraph.appScope.launch {
            try {
                repository.remove(id)
            } catch (e: EngineException) {
                OpsErrors.report(e.message)
            }
        }
    }

    /** 展开日志的轮询 job（id → job，审查 P1-11：有展开才有轮询，关闭即取消）。 */
    private val logPollJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun toggleLogs(id: String) {
        val current = _logs.value
        if (current.containsKey(id)) {
            logPollJobs.remove(id)?.cancel()
            _logs.value = current - id
        } else {
            viewModelScope.launch {
                _logs.value = _logs.value + (id to repository.readLog(id))
            }
            logPollJobs[id] = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    if (!_logs.value.containsKey(id)) break
                    _logs.value = _logs.value + (id to repository.readLog(id))
                }
            }
        }
    }
}
