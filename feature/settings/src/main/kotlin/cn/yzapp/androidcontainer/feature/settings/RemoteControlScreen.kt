package cn.yzapp.androidcontainer.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.billing.EntitlementState

/**
 * 二级页：远程控制（Web 控制台 / Docker API / 审计日志）。
 *
 * 视觉结构：顶部安全提示条 → 「Web 控制台」卡片（开关 + 展开配置）→
 * 「Docker API」卡片 → 审计日志入口卡片。
 *
 * **门禁（m8_m9 方案 §7）**：Web 控制台属于模板包，未解锁时开关置灰、提示去解锁，
 * 且服务不会启动（不留一个只会报 402 的端口）。Docker API 不受门禁影响。
 */
@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
    onOpenUnlock: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlementState.collectAsStateWithLifecycle()
    // 仅 Unlocked 放行；Unknown（首次查询未返回）同样按未解锁处理，避免留下可访问的端口
    val remoteLocked = entitlement !is EntitlementState.Unlocked
    val clipboard = LocalClipboardManager.current
    val copiedText = stringResource(R.string.settings_remote_token_copied)
    var showLanConfirm by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.settings_remote_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        // 安全提示条
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_remote_risk),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Web 控制台（带 token 鉴权；属于模板包，未解锁时置灰）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_remote_web),
                    checked = state.webEnabled,
                    enabled = !remoteLocked,
                    onCheckedChange = viewModel::onWebEnabledChange,
                )
                if (remoteLocked) {
                    RemoteLockedHint(
                        paused = state.webEnabled,
                        onOpenUnlock = onOpenUnlock,
                    )
                }
                AnimatedVisibility(visible = state.webEnabled && !remoteLocked) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HorizontalDivider()
                        OutlinedTextField(
                            value = state.webPortInput,
                            onValueChange = viewModel::onWebPortChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_remote_port_hint)) },
                        )
                        Text(
                            text = stringResource(R.string.settings_remote_restart_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.settings_remote_token),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        CodeBlock(text = state.apiToken ?: "—")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                state.apiToken?.let { clipboard.setText(AnnotatedString(it)) }
                                viewModel.onTokenCopied()
                            }) {
                                Text(
                                    if (state.tokenCopied) copiedText
                                    else stringResource(R.string.settings_remote_token_copy),
                                )
                            }
                            TextButton(onClick = viewModel::onTokenReset) {
                                Text(stringResource(R.string.settings_remote_token_reset))
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_remote_lan),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        CodeBlock(
                            text = state.lanAddress?.let { "http://$it:${state.webPortInput}" }
                                ?: stringResource(R.string.settings_remote_lan_unavailable),
                        )
                    }
                }
            }
        }

        // Docker API（独立开关，无 header 鉴权）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_remote_docker),
                    subtitle = stringResource(R.string.settings_remote_docker_risk),
                    checked = state.dockerEnabled,
                    onCheckedChange = viewModel::onDockerEnabledChange,
                )
                AnimatedVisibility(visible = state.dockerEnabled) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HorizontalDivider()
                        // 局域网暴露（默认关，只绑回环）：开启必须走二次确认
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_remote_docker_lan),
                            subtitle = stringResource(R.string.settings_remote_docker_lan_risk),
                            checked = state.dockerLanEnabled,
                            onCheckedChange = { if (it) showLanConfirm = true else viewModel.onDockerLanChange(false) },
                        )
                        OutlinedTextField(
                            value = state.dockerPortInput,
                            onValueChange = viewModel::onDockerPortChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_remote_port_hint)) },
                        )
                        CodeBlock(
                            text = state.lanAddress?.let { "docker -H tcp://$it:${state.dockerPortInput} ps" }
                                ?: stringResource(R.string.settings_remote_lan_unavailable),
                        )
                    }
                }
            }
        }

        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 审计日志入口
        Card(modifier = Modifier.fillMaxWidth()) {
            SettingsNavRow(
                title = stringResource(R.string.settings_remote_audit),
                onClick = viewModel::loadAudit,
            )
        }
    }

    state.audit?.let { entries ->
        AuditDialog(entries = entries, onDismiss = viewModel::dismissAudit)
    }

    if (showLanConfirm) {
        AlertDialog(
            onDismissRequest = { showLanConfirm = false },
            title = { Text(stringResource(R.string.settings_remote_docker_lan_confirm_title)) },
            text = { Text(stringResource(R.string.settings_remote_docker_lan_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showLanConfirm = false
                    viewModel.onDockerLanChange(true)
                }) {
                    Text(stringResource(R.string.settings_remote_docker_lan_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanConfirm = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

/**
 * 未解锁提示卡：解释 Web 控制台属于模板包，并提供解锁入口。
 *
 * [paused] 表示开关此前已打开（如退款后权益失效）——多给一句「服务已暂停」的说明，
 * 免得用户以为开关坏了。
 */
@Composable
private fun RemoteLockedHint(
    paused: Boolean,
    onOpenUnlock: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_remote_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (paused) {
                Text(
                    text = stringResource(R.string.settings_remote_locked_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onOpenUnlock) {
                Text(stringResource(R.string.settings_remote_unlock))
            }
        }
    }
}

/** 地址 / 令牌 / 命令示例的代码块：浅底圆角 + 等宽字体，可长按选择复制。 */
@Composable
private fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AuditDialog(
    entries: List<RemoteAuditEntry>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_remote_audit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.settings_remote_audit_empty))
                } else {
                    entries.take(50).forEach { entry ->
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.success) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
