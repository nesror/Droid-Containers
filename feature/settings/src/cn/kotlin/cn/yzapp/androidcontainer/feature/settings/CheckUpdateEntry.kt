package cn.yzapp.androidcontainer.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.data.UpdateCheckResult
import kotlinx.coroutines.launch

/**
 * 「检查更新」入口（仅 `cn` 渠道可见，`src/global` 为空壳）：
 * 设置页「关于与帮助」分组内的一行 + 结果弹窗（发现新版本 / 已是最新 / 检查失败）。
 *
 * 检查请求为秒级 HTTP 调用，挂 rememberCoroutineScope 即可；页面销毁时静默取消，无状态残留。
 */
@Composable
internal fun CheckUpdateEntry() {
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SettingsNavRow(
        title = stringResource(R.string.settings_update_title),
        subtitle = stringResource(R.string.settings_update_desc),
        trailingText = if (checking) stringResource(R.string.settings_update_checking) else null,
        onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    result = DataGraph.updateChecker.check()
                    checking = false
                }
            }
        },
    )

    result?.let { r ->
        when (r) {
            is UpdateCheckResult.UpdateAvailable -> AlertDialog(
                onDismissRequest = { result = null },
                title = { Text(stringResource(R.string.settings_update_dialog_title, r.versionName)) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = r.changelog.ifBlank {
                                stringResource(R.string.settings_update_dialog_no_changelog)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            result = null
                            openUrl(context, r.pageUrl)
                        },
                    ) { Text(stringResource(R.string.settings_update_dialog_download)) }
                },
                dismissButton = {
                    TextButton(onClick = { result = null }) {
                        Text(stringResource(R.string.settings_update_dialog_later))
                    }
                },
            )
            UpdateCheckResult.UpToDate -> AlertDialog(
                onDismissRequest = { result = null },
                title = { Text(stringResource(R.string.settings_update_latest_title)) },
                text = { Text(stringResource(R.string.settings_update_latest_text)) },
                confirmButton = {
                    TextButton(onClick = { result = null }) {
                        Text(stringResource(R.string.settings_update_ok))
                    }
                },
            )
            is UpdateCheckResult.Failed -> AlertDialog(
                onDismissRequest = { result = null },
                title = { Text(stringResource(R.string.settings_update_failed_title)) },
                text = { Text(stringResource(R.string.settings_update_failed_reason, r.reason)) },
                confirmButton = {
                    TextButton(onClick = { result = null }) {
                        Text(stringResource(R.string.settings_update_ok))
                    }
                },
            )
        }
    }
}
