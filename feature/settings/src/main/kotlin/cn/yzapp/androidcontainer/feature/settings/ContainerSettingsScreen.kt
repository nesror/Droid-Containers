package cn.yzapp.androidcontainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** 二级页：容器设置（镜像源与 DNS，保存后对新拉取/新启动的容器生效）。 */
@Composable
fun ContainerSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_container_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_mirrors_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = state.mirrorsInput,
                onValueChange = viewModel::onMirrorsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_mirrors_hint)) },
                minLines = 4,
            )
            OutlinedButton(
                onClick = viewModel::probeAndSortMirrors,
                enabled = !state.probing && state.mirrorsInput.isNotBlank(),
            ) {
                Text(
                    if (state.probing) {
                        stringResource(R.string.settings_mirrors_probing)
                    } else {
                        stringResource(R.string.settings_mirrors_probe)
                    },
                )
            }
            state.probeResults.forEach { result ->
                Text(
                    text = if (result.latencyMs != null) {
                        stringResource(R.string.settings_mirrors_probe_rtt, result.host, result.latencyMs)
                    } else {
                        stringResource(R.string.settings_mirrors_probe_unreachable, result.host)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.latencyMs != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_dns_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = state.dnsInput,
                onValueChange = viewModel::onDnsChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_dns_hint)) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_save))
            }
            state.saveError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.saved) {
                Text(
                    text = stringResource(R.string.settings_saved),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
