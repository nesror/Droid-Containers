package cn.yzapp.androidcontainer.feature.images

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.common.formatBytes
import cn.yzapp.androidcontainer.core.data.db.ImageEntity
import cn.yzapp.androidcontainer.core.designsystem.component.EmptyState
import cn.yzapp.androidcontainer.core.designsystem.component.PullProgressCard
import cn.yzapp.androidcontainer.core.designsystem.component.RecommendationCard
import cn.yzapp.androidcontainer.core.designsystem.component.currentWindowWidthClass
import cn.yzapp.androidcontainer.core.model.PullProgress
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

@Composable
fun ImagesScreen(
    onOpenCompose: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ImagesViewModel = viewModel(),
) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 拉取进度直接 collect 仓库 StateFlow（此前读 uiState.pullStates 恒空 map，进度卡永不出现）
    val pullStates by viewModel.pullStates.collectAsStateWithLifecycle()
    // 镜像列表同理：VM 的 inventory 是独立 StateFlow，读 uiState.inventory 恒空（P1-12 残留）
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()

    // SAF 选 tar（docker save 产物）；mime 限 tar/octet-stream，部分文件管理器对 tar 标 octet-stream
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importTar(uri)
    }

    ImagesContent(
        input = input,
        inventory = inventory,
        activeProgress = pullStates[input],
        importing = uiState.importing,
        importMessage = uiState.importMessage,
        importError = uiState.importError,
        onInputChange = viewModel::onInputChange,
        onPull = viewModel::pull,
        onImportClick = {
            importLauncher.launch(arrayOf("application/x-tar", "application/octet-stream"))
        },
        onRemove = viewModel::remove,
        onOpenCompose = onOpenCompose,
        modifier = modifier,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImagesContent(
    input: String,
    inventory: List<ImageEntity>,
    activeProgress: PullProgress?,
    importing: Boolean,
    importMessage: String?,
    importError: String?,
    onInputChange: (String) -> Unit,
    onPull: () -> Unit,
    onImportClick: () -> Unit,
    onRemove: (String) -> Unit,
    onOpenCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 宽窗口（横屏/平板）下镜像卡片自适应多列；窄窗口保持单列
    val widthClass = currentWindowWidthClass()
    LazyVerticalGrid(
        columns =
            if (widthClass == WindowWidthSizeClass.Compact) {
                GridCells.Fixed(1)
            } else {
                GridCells.Adaptive(320.dp)
            },
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxCurrentLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.images_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.images_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 手动逐个拉取很繁琐：告诉用户编排会把服务需要的镜像自动拉下来
        item(span = { GridItemSpan(maxCurrentLineSpan) }) {
            RecommendationCard(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.images_recommend_compose_title),
                description = stringResource(R.string.images_recommend_compose_desc),
                onClick = onOpenCompose,
            )
        }
        item(span = { GridItemSpan(maxCurrentLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.images_input_hint)) },
                )
                Button(onClick = onPull) {
                    Text(stringResource(R.string.images_pull))
                }
            }
        }
        // docker load 导入：SAF 选 tar，本地解压不走网络（离线设备/私有镜像场景）
        item(span = { GridItemSpan(maxCurrentLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = onImportClick,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (importing) {
                            stringResource(R.string.images_importing)
                        } else {
                            stringResource(R.string.images_import)
                        },
                    )
                }
                importMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                importError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        activeProgress?.let { progress ->
            item(span = { GridItemSpan(maxCurrentLineSpan) }) { PullProgressCard(progress) }
        }
        item(span = { GridItemSpan(maxCurrentLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.images_inventory_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (inventory.isNotEmpty()) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.images_count,
                            inventory.size,
                            inventory.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (inventory.isEmpty()) {
            item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                EmptyState(
                    text = stringResource(R.string.images_empty_inventory),
                    icon = Icons.Filled.Search,
                )
            }
        } else {
            items(inventory, key = { it.ref }) { image ->
                ImageRow(image = image, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun ImageRow(
    image: ImageEntity,
    onRemove: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = image.ref, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = formatBytes(image.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(image.ref) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.images_delete))
            }
        }
    }
}
