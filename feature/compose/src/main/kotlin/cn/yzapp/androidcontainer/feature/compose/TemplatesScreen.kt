package cn.yzapp.androidcontainer.feature.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.designsystem.component.StatusChip
import cn.yzapp.androidcontainer.core.designsystem.component.currentWindowWidthClass
import cn.yzapp.androidcontainer.core.engine.compose.ComposeTemplate
import cn.yzapp.androidcontainer.core.engine.compose.TemplateTaxonomy
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

/**
 * 模板库列表（方案 §4.2）：分类分组 + 模板卡片。
 *
 * 门控策略（方案 §6.5）：**`Unknown` 不视为 `Locked`**。仅当明确 `Locked` 时才把卡片直接导向解锁页，
 * 其余情况一律允许进入详情预览，避免网络抖动把已付费用户挡在门外。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onOpenTemplate: (String) -> Unit,
    onOpenUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = viewModel(),
) {
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    // 模板文案按当前 App 语言解析（缺失时逐级回退到 en），分类名/提示同样如此
    val language = rememberTemplateLanguage()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_templates_title)) },
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
        // 宽窗口（横屏/平板）下模板卡片自适应多列；窄窗口保持单列，观感与 LazyColumn 一致
        val widthClass = currentWindowWidthClass()
        LazyVerticalGrid(
            columns =
                if (widthClass == WindowWidthSizeClass.Compact) {
                    GridCells.Fixed(1)
                } else {
                    GridCells.Adaptive(320.dp)
                },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.compose_templates_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Pro 状态条只在收费模板可见的渠道展示（`cn` 渠道全免费，无购买语义）
                    if (viewModel.paidTemplatesVisible) {
                        ProStatusCard(
                            entitlement = entitlement,
                            onOpenUnlock = onOpenUnlock,
                        )
                    }
                    Text(
                        text = stringResource(R.string.compose_templates_free_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            viewModel.groups.forEach { group ->
                // 分类标题横跨整行（网格下不与模板卡片并排）
                item(
                    key = "category-" + group.category.id,
                    span = { GridItemSpan(maxCurrentLineSpan) },
                ) {
                    Text(
                        // 兜底分类可能是引擎补出来的、没有本地化名称，用 App 文案兜底
                        text = group.category.name.resolve(language)
                            .ifBlank { stringResource(R.string.compose_tpl_category_other) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
                items(group.templates, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        language = language,
                        taxonomy = viewModel.taxonomy,
                        entitlement = entitlement,
                        onClick = {
                            when {
                                !template.premium -> onOpenTemplate(template.id)
                                entitlement is EntitlementState.Locked -> onOpenUnlock()
                                else -> onOpenTemplate(template.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Pro 状态条：未解锁给解锁入口，已解锁显示状态，未知期间不闪「未解锁」。 */
@Composable
private fun ProStatusCard(
    entitlement: EntitlementState,
    onOpenUnlock: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pro_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.pro_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (entitlement) {
                is EntitlementState.Unlocked -> StatusChip(
                    label = stringResource(R.string.pro_owned),
                    containerColor = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                )

                EntitlementState.Locked -> Button(onClick = onOpenUnlock) {
                    Text(stringResource(R.string.compose_templates_unlock_all))
                }

                // Unknown：首次查询进行中，先留白，避免误报「未解锁」
                EntitlementState.Unknown -> Unit
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: ComposeTemplate,
    language: String,
    taxonomy: TemplateTaxonomy,
    entitlement: EntitlementState,
    onClick: () -> Unit,
) {
    val locked = template.premium && entitlement !is EntitlementState.Unlocked
    val name = template.name(language).ifBlank { stringResource(R.string.compose_tpl_unknown_name) }
    val desc = template.desc(language)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (locked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.compose_tpl_locked_hint),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (desc.isNotBlank()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = pluralStringResource(
                    R.plurals.compose_service_count,
                    template.services.size,
                    template.services.size,
                ) + " · " + stringResource(
                    R.string.compose_tpl_service_ports,
                    template.containerPorts.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TemplateBadgeRow(
                risk = template.risk,
                premium = template.premium,
                taxonomy = taxonomy,
                language = language,
            )
            if (locked) {
                Text(
                    text = stringResource(R.string.compose_tpl_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
