package cn.yzapp.androidcontainer.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.common.formatBytes
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.designsystem.component.EmptyState
import cn.yzapp.androidcontainer.core.designsystem.component.LocalWindowWidth
import cn.yzapp.androidcontainer.core.designsystem.component.StatusChip

@Composable
fun DashboardScreen(modifier: Modifier = Modifier, viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(state = state, modifier = modifier)
}

@Composable
internal fun DashboardContent(state: DashboardUiState, modifier: Modifier = Modifier) {
    // 关键指标：窄窗口两两成行（竖屏手机），宽窗口（横屏/平板）四个并排一行——
    // 否则 2×2 网格在宽窗口里会被拉成又宽又扁的长条，扫读效率反而下降
    val metrics =
        listOf(
            Metric(stringResource(R.string.dashboard_running), state.runningCount.toString(), RunningAccent),
            Metric(stringResource(R.string.dashboard_total), state.totalCount.toString(), MaterialTheme.colorScheme.primary),
            Metric(stringResource(R.string.dashboard_images), state.imageCount.toString(), MaterialTheme.colorScheme.tertiary),
            Metric(stringResource(R.string.dashboard_storage), formatBytes(state.storageUsedBytes), StorageAccent),
        )
    val metricRows =
        if (LocalWindowWidth.current == WindowWidthSizeClass.Compact) metrics.chunked(2)
        else listOf(metrics)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.dashboard_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.dashboard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 关键指标行（行数由窗口宽度决定，见上）
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                metricRows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { metric ->
                            MetricCard(
                                title = metric.title,
                                value = metric.value,
                                accent = metric.accent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.dashboard_recent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.containers.isEmpty()) {
            item {
                EmptyState(
                    text = stringResource(R.string.dashboard_empty),
                    icon = Icons.Filled.Home,
                )
            }
        } else {
            items(state.containers, key = { it.id }) { container ->
                ContainerRow(container)
            }
        }
    }
}

/** 运行中/存储的强调色：与容器列表的状态色保持同一套语言（绿=在跑、橙=占用）。 */
private val RunningAccent = Color(0xFF2E7D32)
private val StorageAccent = Color(0xFFEF6C00)

/** 单个指标卡的数据（标题 / 数值 / 强调色），行数按窗口宽度动态分组。 */
private data class Metric(val title: String, val value: String, val accent: Color)

@Composable
private fun ContainerRow(container: ContainerEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(container.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    container.imageRef,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(status = container.status)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
