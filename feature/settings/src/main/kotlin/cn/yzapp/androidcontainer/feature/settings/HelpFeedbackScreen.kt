package cn.yzapp.androidcontainer.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

/**
 * 二级页：帮助与反馈
 * 使用帮助（官网文档）/ 问题反馈（GitHub Issues）/ 运行日志导出（随反馈一并提交）。
 */
@Composable
fun HelpFeedbackScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var linkFailed by remember { mutableStateOf(false) }

    // 日志导出成功 → 拉起系统分享面板
    LaunchedEffect(state.pendingShare) {
        state.pendingShare?.let { file ->
            context.startActivity(buildLogShareIntent(context, file))
            viewModel.onShareHandled()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_help_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_help_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            SettingsNavRow(
                title = stringResource(R.string.settings_help_guide),
                subtitle = stringResource(R.string.settings_help_guide_desc),
                onClick = { if (!openUrl(context, AppLinks.HELP)) linkFailed = true },
            )
            HorizontalDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_help_issues),
                subtitle = stringResource(R.string.settings_help_issues_desc),
                onClick = { if (!openUrl(context, AppLinks.ISSUES)) linkFailed = true },
            )
            HorizontalDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_help_logs),
                subtitle = stringResource(R.string.settings_help_logs_desc),
                trailingText = if (state.exporting) stringResource(R.string.settings_exporting) else null,
                onClick = { if (!state.exporting) viewModel.exportLogs() },
            )
        }

        if (linkFailed) {
            Text(
                text = stringResource(R.string.settings_help_open_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 日志文件经 FileProvider 以 text/plain 分享给邮件 / IM 等。 */
private fun buildLogShareIntent(context: android.content.Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.settings_export_share_title))
}
