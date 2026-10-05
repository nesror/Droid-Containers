package cn.yzapp.androidcontainer.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cn.yzapp.androidcontainer.core.data.ComposeProjectState
import cn.yzapp.androidcontainer.core.designsystem.component.EmptyState
import cn.yzapp.androidcontainer.core.designsystem.component.ErrorBanner
import cn.yzapp.androidcontainer.core.designsystem.component.PullProgressCard
import cn.yzapp.androidcontainer.core.designsystem.component.RecommendationCard
import cn.yzapp.androidcontainer.core.designsystem.component.StatusChip
import cn.yzapp.androidcontainer.core.model.PullProgress
import cn.yzapp.androidcontainer.core.model.PullStage

@Composable
fun ComposeProjectsScreen(
    onOpenTemplates: () -> Unit = {},
    /** 打开编辑器：`null` = 新建，非空 = 编辑该 id 的项目（草稿由编辑器页按 NavKey 自行准备）。 */
    onOpenEditor: (String?) -> Unit = {},
    /** 点击项目卡片 → 入栈详情二级页（系统返回 = 回列表，不再退出应用）。 */
    onOpenDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ComposeViewModel = viewModel(),
) {
    val cards by viewModel.projectCards.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pullStates by viewModel.pullStates.collectAsStateWithLifecycle()
    val busyProjectId by viewModel.busyProjectId.collectAsStateWithLifecycle()

    /** up 过程中的自动拉取进度（READY 阶段不展示，FAILED 保留错误详情）。 */
    val activePulls = pullStates.values.filter {
        it.stage != PullStage.READY && it.stage != PullStage.IDLE
    }

    Column(modifier = modifier.fillMaxSize()) {
        error?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = viewModel::dismissError,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ProjectListContent(
            cards = cards,
            activePulls = activePulls.takeIf { busyProjectId != null }.orEmpty(),
            // 草稿由编辑器页按 NavKey 自行准备（审查 P1-11：不再跨 entry 预写共享 VM 状态）
            onNewProject = { onOpenEditor(null) },
            onOpenTemplates = onOpenTemplates,
            onOpenDetail = onOpenDetail,
            onEditProject = { id -> onOpenEditor(id) },
            onDeleteProject = viewModel::deleteProject,
            onUp = { viewModel.up(it) },
            onDown = { viewModel.down(it, removeContainers = false) },
            modifier = Modifier.weight(1f),
        )
    }
}

// -------------------------------------------------------------------- 列表页

@Composable
private fun ProjectListContent(
    cards: List<ComposeProjectUi>,
    activePulls: List<PullProgress>,
    onNewProject: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onUp: (String) -> Unit,
    onDown: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ComposeProjectUi?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProjectListHeader(
                projectCount = cards.size,
                onNewProject = onNewProject,
                onOpenTemplates = onOpenTemplates,
            )
        }
        // 从零写 YAML 门槛高：优先把用户引到模板库（选一个现成的就能跑）
        item {
            RecommendationCard(
                icon = Icons.Filled.Star,
                title = stringResource(R.string.compose_templates_title),
                description = stringResource(R.string.compose_templates_subtitle),
                onClick = onOpenTemplates,
            )
        }
        // up 过程中缺镜像自动拉取：进度卡显示在列表顶部
        if (activePulls.isNotEmpty()) {
            items(activePulls, key = { "pull-" + it.imageRef }) { progress ->
                PullProgressCard(progress)
            }
        }
        if (cards.isEmpty()) {
            item {
                EmptyState(
                    text = stringResource(R.string.compose_empty),
                    icon = Icons.AutoMirrored.Filled.List,
                )
            }
        }
        items(cards, key = { it.id }) { card ->
            ProjectCard(
                card = card,
                onOpenDetail = { onOpenDetail(card.id) },
                onEdit = { onEditProject(card.id) },
                onDelete = { pendingDelete = card },
                onUp = { onUp(card.id) },
                onDown = { onDown(card.id) },
            )
        }
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.compose_delete_confirm_title)) },
            text = { Text(stringResource(R.string.compose_delete_confirm_message, card.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProject(card.id)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.compose_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.compose_cancel))
                }
            },
        )
    }
}

/**
 * 列表页头部：标题 + 说明 + 操作入口。
 *
 * 操作按钮单独占一行（标题本身会折行，与按钮同行会互相挤压），且空态不再重复放按钮。
 * 模板库放在前面并做高亮：从现成模板起步比手写 YAML 省事得多，新建留作次选（描边样式）。
 */
@Composable
private fun ProjectListHeader(
    projectCount: Int,
    onNewProject: () -> Unit,
    onOpenTemplates: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.compose_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (projectCount == 0) {
                    stringResource(R.string.compose_subtitle)
                } else {
                    pluralStringResource(R.plurals.compose_project_count, projectCount, projectCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenTemplates, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.compose_templates_entry))
            }
            OutlinedButton(onClick = onNewProject, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.compose_new_project))
            }
        }
    }
}

/**
 * 项目卡片：标题行（名称 / 服务数 / 状态 / 编辑 / 删除）+ 操作行（Up / Down / 详情）。
 *
 * 编辑与删除是低频破坏性操作，收到标题行右侧；生命周期按钮留在下方，
 * 避免五个控件挤在同一行在小屏上换行或溢出。
 */
@Composable
private fun ProjectCard(
    card: ComposeProjectUi,
    onOpenDetail: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = pluralStringResource(
                            R.plurals.compose_service_count,
                            card.serviceCount,
                            card.serviceCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    label = stringResource(card.state.labelRes()),
                    containerColor = card.state.color(),
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.compose_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.compose_delete))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onUp) { Text(stringResource(R.string.compose_up)) }
                FilledTonalButton(onClick = onDown) { Text(stringResource(R.string.compose_down)) }
                OutlinedButton(onClick = onOpenDetail) { Text(stringResource(R.string.compose_detail)) }
            }
        }
    }
}

// -------------------------------------------------------------------- 状态映射

private fun ComposeProjectState.labelRes(): Int = when (this) {
    ComposeProjectState.RUNNING -> R.string.compose_state_running
    ComposeProjectState.PARTIAL -> R.string.compose_state_partial
    ComposeProjectState.STOPPED -> R.string.compose_state_stopped
    ComposeProjectState.NOT_CREATED -> R.string.compose_state_not_created
    ComposeProjectState.STARTING -> R.string.compose_state_starting
}

private fun ComposeProjectState.color(): Color = when (this) {
    ComposeProjectState.RUNNING -> Color(0xFF2E7D32)
    ComposeProjectState.PARTIAL -> Color(0xFFEF6C00)
    ComposeProjectState.STOPPED -> Color(0xFF616161)
    ComposeProjectState.NOT_CREATED -> Color(0xFF1565C0)
    ComposeProjectState.STARTING -> Color(0xFF00897B)
}
