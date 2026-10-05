package cn.yzapp.androidcontainer.feature.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 项目名输入。默认值为模板 id（ASCII，如 `smarthome`），**不用本地化文案做默认名**，
 * 避免「用户数据被本地化」（方案 §4.3）。
 */
@Composable
internal fun TemplateUseDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(defaultName) { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_tpl_use_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.compose_tpl_use_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.compose_project_name)) },
                    isError = name.isBlank(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.compose_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_cancel)) }
        },
    )
}
