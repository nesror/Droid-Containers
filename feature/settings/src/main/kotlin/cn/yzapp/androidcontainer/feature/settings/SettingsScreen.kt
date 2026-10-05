package cn.yzapp.androidcontainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 设置概览页：只放高频开关与分组入口，具体配置拆到二级页面
 * （容器设置 / 远程控制 / 后台运行 / 帮助与反馈 / 分享应用）。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onOpenContainerSettings: () -> Unit = {},
    onOpenRemoteControl: () -> Unit = {},
    onOpenKeepAlive: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenShare: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appInfo = remember(context) { loadAppInfo(context) }

    // 每次进入页面刷新保活状态（电池白名单可能已在系统设置中变更）
    LaunchedEffect(Unit) { viewModel.refreshKeepAliveState() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // 通用：自启开关（高频）留在概览页，其余进二级页
        SettingsGroup(title = stringResource(R.string.settings_group_general)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_autostart_title),
                subtitle = stringResource(R.string.settings_autostart_subtitle),
                checked = state.autostart,
                onCheckedChange = viewModel::onAutostartChange,
            )
            HorizontalDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_container_title),
                subtitle = stringResource(R.string.settings_container_desc),
                onClick = onOpenContainerSettings,
            )
        }

        // 高级：尾部展示当前状态摘要，不必进页面即可判断是否已开启
        SettingsGroup(title = stringResource(R.string.settings_group_advanced)) {
            SettingsNavRow(
                title = stringResource(R.string.settings_remote_title),
                subtitle = stringResource(R.string.settings_remote_desc),
                trailingText = remoteSummary(state),
                onClick = onOpenRemoteControl,
            )
            HorizontalDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_keepalive_title),
                subtitle = stringResource(R.string.settings_keepalive_entry_desc),
                trailingText = if (state.batteryIgnored) {
                    stringResource(R.string.settings_summary_battery_ok)
                } else {
                    stringResource(R.string.settings_summary_battery_bad)
                },
                onClick = onOpenKeepAlive,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_group_about)) {
            // 渠道接缝：cn 展示「检查更新」，global 为空壳（不渲染任何内容）
            CheckUpdateEntry()
            HorizontalDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_help_title),
                subtitle = stringResource(R.string.settings_help_desc),
                onClick = onOpenHelp,
            )
            HorizontalDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_share_title),
                subtitle = stringResource(R.string.settings_share_desc),
                onClick = onOpenShare,
            )
        }

        Text(
            text = stringResource(
                R.string.settings_about_version,
                appInfo.versionName,
                appInfo.versionCode,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** 概览页远程控制摘要：关闭 / Web :port / Docker :port / 两者。 */
@Composable
private fun remoteSummary(state: SettingsUiState): String {
    val webPort = state.webPortInput.toIntOrNull() ?: 0
    val dockerPort = state.dockerPortInput.toIntOrNull() ?: 0
    return when {
        state.webEnabled && state.dockerEnabled -> stringResource(
            R.string.settings_summary_remote_both,
            webPort,
            dockerPort,
        )
        state.webEnabled -> stringResource(R.string.settings_summary_remote_web, webPort)
        state.dockerEnabled -> stringResource(R.string.settings_summary_remote_docker, dockerPort)
        else -> stringResource(R.string.settings_summary_off)
    }
}
