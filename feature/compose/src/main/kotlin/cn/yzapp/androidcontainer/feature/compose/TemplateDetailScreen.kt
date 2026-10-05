package cn.yzapp.androidcontainer.feature.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.designsystem.component.ContentMaxWidth
import cn.yzapp.androidcontainer.core.engine.compose.ComposeService

/**
 * 模板详情（方案 §4.2 / §5.3）：名称 + 完整描述 + 服务清单 + 提示列表 + 端口直达 + YAML 预览 + 底部操作。
 *
 * 未解锁（含 `Unknown`）时底部按钮替换为「解锁模板包」，但**详情与 YAML 预览始终可看**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailScreen(
    templateId: String,
    onBack: () -> Unit,
    onOpenUnlock: () -> Unit,
    onUseTemplate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = viewModel(),
) {
    val template = viewModel.detailOf(templateId)
    if (template == null) {
        // 目录里没有该 id（例如远端模板被移除）：直接退回上一页
        LaunchedEffect(templateId) { onBack() }
        return
    }
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val usable = viewModel.canUse(template, entitlement)
    var showUseDialog by remember { mutableStateOf(false) }
    // 模板文案按当前 App 语言解析（缺失时逐级回退到 en）
    val language = rememberTemplateLanguage()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = {
                    Text(template.name(language).ifBlank { stringResource(R.string.compose_tpl_unknown_name) })
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compose_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // 宽窗口下单列内容限宽居中（手机竖屏不受影响）
        ContentMaxWidth(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            TemplateBadgeRow(
                risk = template.risk,
                premium = template.premium,
                taxonomy = viewModel.taxonomy,
                language = language,
            )

            val desc = template.desc(language)
            if (desc.isNotBlank()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            TemplateNoteList(
                notes = template.noteIds.mapNotNull { viewModel.taxonomy.note(it) },
                language = language,
                webPorts = template.webPorts,
            )

            // 端口不映射：直接给出可以用系统浏览器打开的地址
            if (template.webPorts.isNotEmpty()) {
                PortShortcuts(ports = template.webPorts)
            }

            SectionTitle(stringResource(R.string.compose_tpl_services_label))
            template.services.forEach { service -> ServiceRow(service) }

            if (template.issues.isNotEmpty()) {
                SectionTitle(stringResource(R.string.compose_issues))
                template.issues.forEach { issue ->
                    Text(
                        text = issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle(stringResource(R.string.compose_tpl_yaml_preview))
            YamlPreview(yaml = template.yaml)

            if (usable) {
                Button(
                    onClick = { showUseDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.compose_tpl_use)) }
            } else {
                Button(
                    onClick = onOpenUnlock,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.compose_templates_unlock_all)) }
            }
            if (template.premium && !usable) {
                Text(
                    text = stringResource(R.string.compose_tpl_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }

    if (showUseDialog) {
        TemplateUseDialog(
            defaultName = template.suggestedProjectName,
            onConfirm = { name ->
                showUseDialog = false
                viewModel.handOffDraft(template, name)
                onUseTemplate(template.id)
            },
            onDismiss = { showUseDialog = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ServiceRow(service: ComposeService) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = service.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.compose_service_image, service.image),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (service.ports.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.compose_tpl_service_ports,
                        service.ports.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
    }
}

/** YAML 只读预览：等宽 + 限定高度纵向滚动 + 一键复制。 */
@Composable
private fun YamlPreview(yaml: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = yaml,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
        OutlinedButton(onClick = { copyYaml(context, yaml) }) {
            Text(stringResource(R.string.compose_tpl_yaml_copy))
        }
    }
}

private fun copyYaml(context: Context, yaml: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("compose-yaml", yaml))
    Toast.makeText(context, R.string.compose_tpl_yaml_copied, Toast.LENGTH_SHORT).show()
}
