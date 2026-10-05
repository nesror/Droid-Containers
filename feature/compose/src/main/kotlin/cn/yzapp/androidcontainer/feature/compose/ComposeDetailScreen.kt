package cn.yzapp.androidcontainer.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.designsystem.component.ErrorBanner
import cn.yzapp.androidcontainer.core.designsystem.component.LogViewer
import cn.yzapp.androidcontainer.core.designsystem.component.PullProgressCard
import cn.yzapp.androidcontainer.core.designsystem.component.SecondaryScaffold
import cn.yzapp.androidcontainer.core.designsystem.component.StatusChip
import cn.yzapp.androidcontainer.core.model.PullStage

/**
 * 编排项目详情（独立二级页）。
 *
 * 详情从编排列表页的内嵌视图改为独立 NavKey + entry：系统返回 = 出栈回列表，
 * 不再直接退出应用。[projectId] 进栈时重新绑定详情状态（视图模型现在属于本条目，
 * 见 Navigation 的 ViewModelStore 装饰器）。
 */
@Composable
fun ComposeDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ComposeViewModel = viewModel(),
) {
    // 进入详情：绑定 VM 的详情状态（进程恢复后 VM 里没有这个 id，必须重新 open）
    LaunchedEffect(projectId) { viewModel.openDetail(projectId) }
    // 离开详情：清掉展开的日志轮询等临时状态（无论从哪个路径退出）
    DisposableEffect(Unit) { onDispose { viewModel.closeDetail() } }

    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val startupIssues by viewModel.startupIssues.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pullStates by viewModel.pullStates.collectAsStateWithLifecycle()
    val busyProjectId by viewModel.busyProjectId.collectAsStateWithLifecycle()

    // 删除成功后 VM 会把 openedProjectId 清空（详情变 null）→ 自动退回列表页。
    // 用「曾经可见」判定，避免刚进入时详情尚未生成的空档被误判为删除而弹回。
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(detail) {
        if (detail != null) opened = true else if (opened) onBack()
    }

    SecondaryScaffold(
        title = detail?.projectName ?: stringResource(R.string.compose_detail),
        onBack = onBack,
        modifier = modifier,
        actions = {
            // 草稿由编辑页按 projectId 自行准备（审查 P1-11：不再跨 entry 共享 VM 状态）
            IconButton(onClick = { if (detail != null) onEdit() }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.compose_edit))
            }
            IconButton(onClick = { detail?.let { viewModel.deleteProject(it.projectId) } }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.compose_delete))
            }
        },
    ) {
        error?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::dismissError, modifier = Modifier.fillMaxWidth())
        }

        val current = detail ?: return@SecondaryScaffold

        // up 过程中缺镜像自动拉取：进度卡显示在操作按钮下方（仅本项目 busy 期间）
        val activePulls = pullStates.values.filter {
            it.stage != PullStage.READY && it.stage != PullStage.IDLE
        }
        activePulls.takeIf { busyProjectId == current.projectId }.orEmpty().forEach { progress ->
            PullProgressCard(progress, Modifier.fillMaxWidth())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { viewModel.up(current.projectId) },
                enabled = !current.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.compose_up))
            }
            FilledTonalButton(
                onClick = { viewModel.down(current.projectId, removeContainers = false) },
                enabled = !current.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.compose_down))
            }
        }
        Text(
            text = stringResource(R.string.compose_ports_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        current.parseError?.let { message ->
            ErrorBanner(message = message, onDismiss = {}, modifier = Modifier.fillMaxWidth())
        }
        val issues = (current.issues + startupIssues).distinctBy { it.service to it.message }
        if (issues.isNotEmpty()) {
            IssueList(issues = issues)
        }

        current.services.forEach { service ->
            val containerId = service.containerId
            ServiceCard(
                service = service,
                logLines = containerId?.let { logs[it] }.orEmpty(),
                logsExpanded = containerId != null && logs.containsKey(containerId),
                onStart = { containerId?.let(viewModel::startService) },
                onStop = { containerId?.let(viewModel::stopService) },
                onToggleLogs = { containerId?.let(viewModel::toggleLogs) },
                onOpenPort = viewModel::openServicePage,
            )
        }
    }
}

@Composable
private fun ServiceCard(
    service: ComposeServiceUi,
    logLines: List<String>,
    logsExpanded: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleLogs: () -> Unit,
    onOpenPort: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(service.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.compose_service_image, service.image),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    label = stringResource(
                        if (service.running) R.string.compose_running else R.string.compose_stopped,
                    ),
                    containerColor = if (service.running) Color(0xFF2E7D32) else Color(0xFF616161),
                )
            }
            if (service.dependsOn.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.compose_service_depends,
                        service.dependsOn.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (service.ports.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.compose_service_ports, service.ports.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (service.containerId == null) {
                Text(
                    text = stringResource(R.string.compose_not_created_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // FlowRow：空间不足时按钮整体换行，避免文案被压成两行（长语言尤甚）
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { if (service.running) onStop() else onStart() }) {
                        Text(
                            stringResource(
                                if (service.running) R.string.compose_stop else R.string.compose_start,
                            ),
                        )
                    }
                    FilledTonalButton(onClick = onToggleLogs) {
                        Text(
                            stringResource(
                                if (logsExpanded) R.string.compose_hide_logs else R.string.compose_logs,
                            ),
                        )
                    }
                    // 有端口的服务直达：多端口下拉选，单端口直接开（与容器页「浏览器」同一语义）
                    if (service.running && service.webPorts.isNotEmpty()) {
                        BrowserPortButton(ports = service.webPorts, onOpenPort = onOpenPort)
                    }
                }
                if (logsExpanded) {
                    LogViewer(
                        lines = logLines,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    )
                }
            }
        }
    }
}

/** 服务直达按钮：单端口直开；多端口展开菜单选择。 */
@Composable
private fun BrowserPortButton(ports: List<Int>, onOpenPort: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = {
            val single = ports.singleOrNull()
            if (single != null) onOpenPort(single) else expanded = true
        }) {
            Text(stringResource(R.string.compose_open_browser))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ports.forEach { port ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.compose_ports_url, port)) },
                    onClick = {
                        expanded = false
                        onOpenPort(port)
                    },
                )
            }
        }
    }
}
