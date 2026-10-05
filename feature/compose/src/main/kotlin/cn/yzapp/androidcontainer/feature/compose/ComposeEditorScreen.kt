package cn.yzapp.androidcontainer.feature.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.designsystem.component.SecondaryScaffold

/**
 * 编排项目编辑页（新建 / 编辑共用一页）。
 *
 * 编辑器从编排列表内嵌卡片改为独立二级页：YAML 需要整屏高度，独立页面也让「保存 / 取消」
 * 有明确的返回语义。
 *
 * [projectId] 由 NavKey 带进来（`null` = 新建），本页进入时让 ViewModel 按它准备草稿
 * ——审查 P1-11：草稿不再由列表页跨 entry 预写进共享 VM，会话恢复后也能重新取到。
 */
@Composable
fun ComposeEditorScreen(
    projectId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ComposeViewModel = viewModel(),
) {
    // 进入即准备草稿（模板「使用此模板」的草稿也在这里被消费）
    LaunchedEffect(projectId) { viewModel.openEditor(projectId) }

    val editor by viewModel.editor.collectAsStateWithLifecycle()

    // 保存成功（或外部收起编辑器）后自动退回上一页；首次进入时草稿可能还没落到 ViewModel，
    // 故用「曾经可见」判定，避免刚进来就被弹回去。
    // 进程恢复后 VM 重建 → editor.visible 为默认 false 且 closeEditor 写入相等值不发射
    // （StateFlow 去重），LaunchedEffect 永不触发 → 返回键失灵；因此 onBack 直接
    // 「关闭 + 返回」双动作，不再依赖状态流转（P1-19）。
    var opened by remember { mutableStateOf(editor.visible) }
    LaunchedEffect(editor.visible) {
        if (editor.visible) opened = true else if (opened) onDone()
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(viewModel::importYaml)
        }

    SecondaryScaffold(
        title = stringResource(
            if (editor.projectId == null) R.string.compose_editor_new_title
            else R.string.compose_editor_edit_title,
        ),
        onBack = {
            // 先关掉自动返回闸门再弹栈：否则 closeEditor 让 editor.visible 变 false 会触发
            // 上面的 LaunchedEffect 再调一次 onDone —— 连弹两次把返回栈清空，
            // NavDisplay 重组时 require(backStack.isNotEmpty()) 直接崩溃（2026-09-30 闪退）。
            opened = false
            viewModel.closeEditor()
            onDone()
        },
        modifier = modifier,
        actions = {
            TextButton(
                onClick = viewModel::saveProject,
                enabled = !editor.busy && editor.name.isNotBlank() && editor.error == null,
            ) {
                Text(stringResource(R.string.compose_save))
            }
        },
    ) {
        OutlinedTextField(
            value = editor.name,
            onValueChange = viewModel::onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.compose_project_name)) },
        )

        // YAML 区：小标题独占一行（与按钮同行会被挤成两行），两个入口等宽平分
        Text(
            text = stringResource(R.string.compose_yaml),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/x-yaml", "text/yaml", "text/plain", "*/*"),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.compose_import))
            }
            OutlinedButton(
                onClick = viewModel::loadSample,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.compose_sample))
            }
        }

        OutlinedTextField(
            value = editor.yaml,
            onValueChange = viewModel::onYamlChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = editor.error != null,
        )

        val parseError = editor.error
        if (parseError != null) {
            Text(
                text = stringResource(R.string.compose_parse_error, parseError),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            editor.errorLine?.let { line -> YamlErrorSnippet(yaml = editor.yaml, errorLine = line) }
        } else {
            Text(
                text = stringResource(R.string.compose_parse_ok, editor.serviceNames.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (editor.issues.isNotEmpty()) {
            IssueList(issues = editor.issues)
        }
    }
}
