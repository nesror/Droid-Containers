package cn.yzapp.androidcontainer.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.common.formatBytes
import cn.yzapp.androidcontainer.core.designsystem.R
import cn.yzapp.androidcontainer.core.model.PullProgress
import cn.yzapp.androidcontainer.core.model.PullStage

/**
 * 镜像拉取进度卡片（镜像页 / 容器创建 / 编排 up 共用）。
 * DOWNLOADING 显示字节进度条；FAILED 显示错误详情；其余阶段显示阶段文案。
 */
@Composable
fun PullProgressCard(progress: PullProgress, modifier: Modifier = Modifier) {
    when (progress.stage) {
        PullStage.DOWNLOADING -> {
            // 优先用跨层累计字节：进度条单调前进，不会随层切换回跳；旧引擎无累计值时退回当前层
            val (doneBytes, allBytes) = if (progress.overallTotalBytes > 0) {
                progress.overallDownloadedBytes to progress.overallTotalBytes
            } else {
                progress.downloadedBytes to progress.totalBytes
            }
            val percent = if (allBytes > 0) (doneBytes * 100 / allBytes).toInt() else 0
            val parts = buildList {
                progress.mirrorHost?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (progress.totalLayers > 0) {
                    add(stringResource(R.string.ds_pull_layer_progress, progress.currentLayer, progress.totalLayers))
                }
                add("${formatBytes(doneBytes)} / ${formatBytes(allBytes)}")
            }
            ProgressCard(
                title = "${progress.imageRef} · ${stringResource(R.string.ds_pull_stage_downloading)}",
                progress = percent / 100f,
                subtitle = parts.joinToString(" · "),
                modifier = modifier,
            )
        }
        PullStage.FAILED -> Card(modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "${progress.imageRef} · ${stringResource(R.string.ds_pull_stage_failed)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = progress.message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            val subtitle = buildString {
                append(stageLabel(progress.stage))
                if (progress.totalLayers > 0) {
                    append(" · ")
                    append(stringResource(R.string.ds_pull_layer_progress, progress.currentLayer, progress.totalLayers))
                }
            }
            ProgressCard(
                title = progress.imageRef,
                progress = 0f,
                subtitle = subtitle,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun stageLabel(stage: PullStage): String = when (stage) {
    PullStage.RESOLVING_MANIFEST -> stringResource(R.string.ds_pull_stage_resolving)
    PullStage.DOWNLOADING -> stringResource(R.string.ds_pull_stage_downloading)
    PullStage.EXTRACTING, PullStage.CREATING_CONTAINER -> stringResource(R.string.ds_pull_stage_extracting)
    PullStage.READY -> stringResource(R.string.ds_pull_stage_done)
    PullStage.FAILED -> stringResource(R.string.ds_pull_stage_failed)
    else -> stage.name
}
