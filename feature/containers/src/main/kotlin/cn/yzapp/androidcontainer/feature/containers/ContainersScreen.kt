package cn.yzapp.androidcontainer.feature.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.data.ContainerRuntime
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.designsystem.component.EmptyState
import cn.yzapp.androidcontainer.core.designsystem.component.ErrorBanner
import cn.yzapp.androidcontainer.core.designsystem.component.LogViewer
import cn.yzapp.androidcontainer.core.designsystem.component.RecommendationCard
import cn.yzapp.androidcontainer.core.designsystem.component.StatusChip

@Composable
fun ContainersScreen(
    onNewContainer: () -> Unit = {},
    onOpenCompose: () -> Unit = {},
    onOpenTerminal: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: ContainersViewModel = viewModel(),
) {
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val runtimeStates by viewModel.runtimeStates.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val portEditor by viewModel.portEditor.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        error?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = viewModel::dismissError,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ContainersContent(
            containers = containers,
            runtimeStates = runtimeStates,
            logs = logs,
            // 表单展开由新建页自己负责（审查 P1-11：不再跨 entry 预置共享 VM 状态）
            onNewContainer = onNewContainer,
            onOpenCompose = onOpenCompose,
            onStart = viewModel::start,
            onStop = viewModel::stop,
            onRemove = viewModel::remove,
            onToggleLogs = viewModel::toggleLogs,
            onExec = viewModel::exec,
            onOpenTerminal = onOpenTerminal,
            onOpenBrowser = viewModel::showPortEditor,
            modifier = Modifier.weight(1f),
        )
    }

    portEditor?.let { editor ->
        PortEditorDialog(
            editor = editor,
            onInputChange = viewModel::onPortInputChange,
            onOpenSaved = viewModel::openSavedPort,
            onRemovePort = viewModel::removePort,
            onRememberAndOpen = viewModel::rememberAndOpenPort,
            onDismiss = viewModel::dismissPortEditor,
        )
    }
}

@Composable
private fun ContainersContent(
    containers: List<ContainerEntity>,
    runtimeStates: Map<String, ContainerRuntime>,
    logs: Map<String, List<String>>,
    onNewContainer: () -> Unit,
    onOpenCompose: () -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleLogs: (String) -> Unit,
    onExec: (String, String) -> Unit,
    onOpenTerminal: (String, String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ContainersHeader(containerCount = containers.size, onNewContainer = onNewContainer) }
        // 单个容器要一个个手动创建：这里顺势把用户引到更省事的编排（多服务一起跑、镜像自动拉取）
        item {
            RecommendationCard(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.containers_recommend_compose_title),
                description = stringResource(R.string.containers_recommend_compose_desc),
                onClick = onOpenCompose,
            )
        }
        if (containers.isEmpty()) {
            item {
                EmptyState(
                    text = stringResource(R.string.containers_empty),
                    icon = Icons.Filled.PlayArrow,
                )
            }
        } else {
            items(containers, key = { it.id }) { container ->
                ContainerRow(
                    container = container,
                    runtime = runtimeStates[container.id],
                    logsExpanded = logs.containsKey(container.id),
                    logLines = logs[container.id].orEmpty(),
                    onStart = onStart,
                    onStop = onStop,
                    onRemove = onRemove,
                    onToggleLogs = onToggleLogs,
                    onExec = onExec,
                    onOpenTerminal = onOpenTerminal,
                    onOpenBrowser = onOpenBrowser,
                )
            }
        }
    }
}

/**
 * 列表页头部：标题 + 说明 + 新建入口。
 *
 * 与编排页保持同一结构——标题与副标题一段、主操作单独一行（小屏上按钮不会与标题抢宽度）。
 */
@Composable
private fun ContainersHeader(containerCount: Int, onNewContainer: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.containers_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (containerCount == 0) {
                    stringResource(R.string.containers_subtitle)
                } else {
                    pluralStringResource(R.plurals.containers_count, containerCount, containerCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onNewContainer, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.containers_create))
        }
    }
}

/**
 * 容器卡片：标题行（名称 / 镜像 / 状态 / 删除）+ 操作行（启停 / 日志 / 浏览器）。
 *
 * 布局与编排页的卡片对齐：删除这类破坏性操作收到标题行右侧，
 * 操作行只留下高频按钮，避免四五个按钮挤在一行小屏溢出。
 */
@Composable
private fun ContainerRow(
    container: ContainerEntity,
    runtime: ContainerRuntime?,
    logsExpanded: Boolean,
    logLines: List<String>,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleLogs: (String) -> Unit,
    onExec: (String, String) -> Unit,
    onOpenTerminal: (String, String) -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    val running = runtime == ContainerRuntime.RUNNING
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(container.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        container.imageRef,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 编排创建的容器生命周期归项目管（compose down 会一并清掉），单独创建的要区分开
                    if (container.isComposeService) {
                        Text(
                            text = stringResource(R.string.containers_compose_managed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                StatusChip(
                    label = stringResource(
                        if (running) R.string.containers_running else R.string.containers_stopped,
                    ),
                    containerColor = if (running) Color(0xFF2E7D32) else Color(0xFF616161),
                )
                IconButton(onClick = { onRemove(container.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.containers_delete),
                    )
                }
            }
            // FlowRow：空间不足时按钮整体换行，避免文案被压成两行（长语言尤甚）
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { if (running) onStop(container.id) else onStart(container.id) }) {
                    Text(
                        stringResource(if (running) R.string.containers_stop else R.string.containers_start),
                    )
                }
                FilledTonalButton(onClick = { onToggleLogs(container.id) }) {
                    Text(
                        stringResource(
                            if (logsExpanded) R.string.containers_hide_logs else R.string.containers_logs,
                        ),
                    )
                }
                if (running) {
                    FilledTonalButton(onClick = { onOpenBrowser(container.id) }) {
                        Text(stringResource(R.string.containers_open_browser))
                    }
                }
                // 终端：独立 proot shell 会话，rootfs 在即可进（不要求容器运行态）
                FilledTonalButton(onClick = { onOpenTerminal(container.id, container.name) }) {
                    Text(stringResource(R.string.containers_terminal))
                }
            }
            if (logsExpanded) {
                LogViewer(
                    lines = logLines,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                )
            }
            if (logsExpanded && running) {
                ExecInput(onExec = { onExec(container.id, it) })
            }
        }
    }
}

/** exec 终端输入行：命令写入容器 sh 的 stdin，输出见上方日志（输入重定向方案）。 */
@Composable
private fun ExecInput(onExec: (String) -> Unit) {
    var command by remember { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(R.string.containers_exec_hint)) },
        )
        FilledTonalButton(
            onClick = {
                if (command.isNotBlank()) {
                    onExec(command)
                    command = ""
                }
            },
        ) {
            Text(stringResource(R.string.containers_exec_send))
        }
    }
}

/**
 * 容器网页端口编辑：列出已保存端口（点击直接打开，可删除）；
 * 输入新端口后「打开并记住」。URL 固定 http://127.0.0.1:port（proot 监听本机端口）。
 */
@Composable
private fun PortEditorDialog(
    editor: PortEditorUiState,
    onInputChange: (String) -> Unit,
    onOpenSaved: (Int) -> Unit,
    onRemovePort: (Int) -> Unit,
    onRememberAndOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inputPort = editor.input.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.containers_ports_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editor.ports.isEmpty()) {
                    Text(
                        text = stringResource(R.string.containers_ports_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                editor.ports.forEach { port ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { onOpenSaved(port) }) {
                            Text(stringResource(R.string.containers_ports_url, port))
                        }
                        IconButton(onClick = { onRemovePort(port) }, modifier = Modifier.weight(1f, fill = false)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.containers_ports_remove),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = editor.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.containers_ports_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRememberAndOpen, enabled = inputPort != null) {
                Text(stringResource(R.string.containers_ports_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.containers_cancel))
            }
        },
    )
}
