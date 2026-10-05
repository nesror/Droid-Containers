package cn.yzapp.androidcontainer.feature.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.designsystem.component.PullProgressCard
import cn.yzapp.androidcontainer.core.designsystem.component.SecondaryScaffold
import cn.yzapp.androidcontainer.core.model.PullStage

/**
 * 新建容器（独立二级页）：docker 命令导入 + 名称/镜像/自启 + 创建。
 *
 * 表单不再内嵌在容器列表里，避免列表被长表单挤占，也让创建过程有完整的返回语义。
 */
@Composable
fun NewContainerScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContainersViewModel = viewModel(),
) {
    val creation by viewModel.creation.collectAsStateWithLifecycle()
    val pullStates by viewModel.pullStates.collectAsStateWithLifecycle()
    val working by viewModel.creationWorking.collectAsStateWithLifecycle()

    // 进入即展开表单：本页有自己的 ViewModel（entry 级），列表页不再预置共享状态（审查 P1-11）
    LaunchedEffect(Unit) { viewModel.toggleCreation(true) }

    // 创建成功（表单收起）或用户返回后自动退栈；首次进入时表单可能尚未展开，故用「曾经展开」判定
    var opened by remember { mutableStateOf(creation.expanded) }
    LaunchedEffect(creation.expanded) {
        if (creation.expanded) opened = true else if (opened) onDone()
    }

    val clipboard = LocalClipboardManager.current

    SecondaryScaffold(
        title = stringResource(R.string.containers_create),
        // 进程恢复后 creation.expanded 回落默认 false，toggleCreation(false) 写入相等值不发射
        // → LaunchedEffect 不触发 → 返回键失灵；直接「收起 + 返回」双动作（P1-19）
        onBack = {
            viewModel.toggleCreation(false)
            onDone()
        },
        modifier = modifier,
    ) {
        // 从 docker run 命令导入：粘贴 → 解析 → 填充下方表单
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.containers_docker_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = creation.dockerCommand,
                    onValueChange = viewModel::onDockerCommandChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    placeholder = { Text(stringResource(R.string.containers_docker_placeholder)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            clipboard.getText()?.toString()?.let(viewModel::onDockerCommandChange)
                        },
                    ) {
                        Text(stringResource(R.string.containers_docker_paste))
                    }
                    Button(
                        onClick = viewModel::applyDockerCommand,
                        enabled = creation.dockerCommand.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.containers_docker_parse))
                    }
                }
                creation.dockerParse?.let { result ->
                    val text = if (result.ok) {
                        buildAnnotatedString {
                            append(stringResource(R.string.containers_docker_parse_ok))
                            if (result.unsupported.isNotEmpty()) {
                                append(' ')
                                append(
                                    stringResource(
                                        R.string.containers_docker_unsupported,
                                        result.unsupported.joinToString(", "),
                                    ),
                                )
                            }
                        }
                    } else {
                        AnnotatedString(stringResource(R.string.containers_docker_parse_fail))
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = creation.name,
            onValueChange = viewModel::onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.containers_name)) },
        )
        OutlinedTextField(
            value = creation.imageRef,
            onValueChange = viewModel::onImageRefChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.containers_image)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.containers_autostart),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = creation.autoStart, onCheckedChange = viewModel::onAutoStartChange)
        }

        // 镜像缺失自动拉取：创建过程中显示实时下载进度（READY 阶段一闪而过，不展示）
        if (working) {
            pullStates[creation.imageRef.trim()]
                ?.takeIf { it.stage != PullStage.READY && it.stage != PullStage.IDLE }
                ?.let { PullProgressCard(it, Modifier.fillMaxWidth()) }
        }
        creation.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = viewModel::create,
            enabled = !working && creation.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.containers_create))
        }
    }
}
