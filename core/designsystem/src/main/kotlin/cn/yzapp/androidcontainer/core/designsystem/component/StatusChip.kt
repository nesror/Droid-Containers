package cn.yzapp.androidcontainer.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.designsystem.R
import cn.yzapp.androidcontainer.core.model.ContainerStatus

/**
 * 容器状态徽标（方案 §5 容器列表/仪表盘）。
 */
@Composable
fun StatusChip(
    status: ContainerStatus,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        when (status) {
            ContainerStatus.CREATED -> R.string.ds_status_created
            ContainerStatus.RUNNING -> R.string.ds_status_running
            ContainerStatus.STOPPED -> R.string.ds_status_stopped
            ContainerStatus.EXITED -> R.string.ds_status_exited
            ContainerStatus.FAILED -> R.string.ds_status_failed
        },
    )
    val containerColor = when (status) {
        ContainerStatus.CREATED -> Color(0xFF1565C0)
        ContainerStatus.RUNNING -> Color(0xFF2E7D32)
        ContainerStatus.STOPPED -> Color(0xFF616161)
        ContainerStatus.EXITED -> Color(0xFFEF6C00)
        ContainerStatus.FAILED -> Color(0xFFC62828)
    }
    StatusChip(label = label, containerColor = containerColor, modifier = modifier)
}

/**
 * 通用徽标：调用方自带文案与配色（编排页的项目聚合状态、镜像拉取阶段等复用同一视觉）。
 */
@Composable
fun StatusChip(
    label: String,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
