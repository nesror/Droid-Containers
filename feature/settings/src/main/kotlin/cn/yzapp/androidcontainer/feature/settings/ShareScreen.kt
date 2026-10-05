package cn.yzapp.androidcontainer.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 二级页：分享应用（下载地址 + 复制 + 系统分享 + 打开下载页）。 */
@Composable
fun ShareScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appInfo = remember(context) { loadAppInfo(context) }
    var copied by remember { mutableStateOf(false) }

    // 复制提示 2 秒后自动消失
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_share_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_share_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 应用标识 + 下载地址（可选择复制）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = appInfo.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.settings_about_version,
                        appInfo.versionName,
                        appInfo.versionCode,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_share_url_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SelectionContainer {
                    Text(
                        text = AppLinks.DOWNLOAD,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Button(
            onClick = {
                val shareText = context.getString(R.string.settings_share_text, AppLinks.DOWNLOAD)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, appInfo.name)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(
                    Intent.createChooser(send, context.getString(R.string.settings_share_chooser)),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_share_send))
        }

        OutlinedButton(
            onClick = { openUrl(context, AppLinks.DOWNLOAD) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_share_open))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(AppLinks.DOWNLOAD))
                copied = true
            }) {
                Text(stringResource(R.string.settings_share_copy))
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.settings_share_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
