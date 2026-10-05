package cn.yzapp.androidcontainer.feature.compose

import cn.yzapp.androidcontainer.core.engine.compose.ComposeIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** up/down 进行中的操作类型；STARTING 瞬态只对 up（含镜像拉取）生效，down 不改卡片状态。 */
internal enum class BusyOp { UP, DOWN }

/**
 * 编排操作状态（审查 P1-11）：列表页与详情页共享的「正在进行中」事实。
 *
 * 原实现把它放在 `ComposeViewModel` 里，靠「全 App 共用一个 Activity 级 VM 实例」
 * 保证列表页发起的 up 在详情页可见。现在每个导航条目有自己的 VM，这类状态属于
 * **应用**而非某个页面，因此上提到进程级单例：
 * 列表页点 up → 详情页同样看到 busy、拉取进度与置灰按钮，不会重复触发。
 *
 * 任务本身跑在 `DataGraph.appScope`，与页面生命周期彻底解耦（见 ComposeViewModel.up/down）。
 */
internal object ComposeOps {

    private val _busyProjectId = MutableStateFlow<String?>(null)

    /** 正在执行 up/down 的项目 id：编排页据此显示镜像自动拉取进度。 */
    val busyProjectId: StateFlow<String?> = _busyProjectId.asStateFlow()

    private val _busyOp = MutableStateFlow<BusyOp?>(null)

    val busyOp: StateFlow<BusyOp?> = _busyOp.asStateFlow()

    private val _startupIssues = MutableStateFlow<List<ComposeIssue>>(emptyList())

    /** 最近一次 up 的服务启动提示（列表页与详情页共用一条）。 */
    val startupIssues: StateFlow<List<ComposeIssue>> = _startupIssues.asStateFlow()

    /** 标记 up 开始：先清掉上一轮的启动提示，再置 busy。 */
    fun beginUp(projectId: String) {
        _startupIssues.value = emptyList()
        _busyOp.value = BusyOp.UP
        _busyProjectId.value = projectId
    }

    fun beginDown(projectId: String) {
        _busyOp.value = BusyOp.DOWN
        _busyProjectId.value = projectId
    }

    fun setStartupIssues(issues: List<ComposeIssue>) {
        _startupIssues.value = issues
    }

    /** 进入详情时清掉上一轮提示，避免把别的项目的结果带进来。 */
    fun clearStartupIssues() {
        _startupIssues.value = emptyList()
    }

    fun finish() {
        _busyProjectId.value = null
        _busyOp.value = null
    }
}
