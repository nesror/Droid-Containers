package cn.yzapp.androidcontainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** 二级页：后台运行保活（电池白名单 / 厂商自启设置 / 当前厂商注意事项 / 回看引导）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeepAliveScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从系统设置页返回时电池白名单可能已变更，重新读一次
    LaunchedEffect(Unit) { viewModel.refreshKeepAliveState() }

    SettingsScaffold(
        title = stringResource(R.string.settings_keepalive_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_keepalive_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 状态：电池白名单 + 保活服务工作状态
        Text(
            text = if (state.batteryIgnored) {
                stringResource(R.string.settings_keepalive_battery_ok)
            } else {
                stringResource(R.string.settings_keepalive_battery_bad)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.batteryIgnored) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Text(
            text = if (state.runningContainers > 0) {
                stringResource(R.string.settings_keepalive_service_active, state.runningContainers)
            } else {
                stringResource(R.string.settings_keepalive_service_idle)
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        // 按钮横排放不下时自动换行，避免文本被压缩成竖排
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = viewModel::requestBatteryWhitelist, enabled = !state.batteryIgnored) {
                Text(
                    if (state.batteryIgnored) stringResource(R.string.settings_keepalive_battery_done)
                    else stringResource(R.string.settings_keepalive_battery_allow),
                )
            }
            OutlinedButton(onClick = viewModel::openVendorSettings) {
                Text(stringResource(R.string.settings_keepalive_vendor_open))
            }
        }

        // 当前厂商注意事项（国内定制系统配置步骤）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(vendorNameRes(state.vendor)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(vendorStepsRes(state.vendor)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.keepalive_note_common),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TextButton(onClick = onReplayOnboarding) {
            Text(stringResource(R.string.settings_keepalive_replay))
        }
    }
}
