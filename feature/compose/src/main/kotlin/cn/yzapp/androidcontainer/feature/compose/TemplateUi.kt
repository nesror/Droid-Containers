package cn.yzapp.androidcontainer.feature.compose

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.designsystem.component.StatusChip
import cn.yzapp.androidcontainer.core.engine.compose.TemplateNoteInfo
import cn.yzapp.androidcontainer.core.engine.compose.TemplateRisk
import cn.yzapp.androidcontainer.core.engine.compose.TemplateTaxonomy

/**
 * 当前 App 语言的 BCP-47 标签（如 `zh-Hant-TW`）。
 *
 * 用**App 语言**而不是系统语言：将来接入 per-app language 时同样正确；
 * 模板文案（`name` / `desc` / 共享提示）按它解析，缺失时逐级回退到 `en`。
 * 读的是 Compose 的 configuration，语言变化会触发重组。
 */
@Composable
internal fun rememberTemplateLanguage(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/** 风险标签配色：已验证用正向色，实验性用警示色（与项目既有 StatusChip 用法一致）。 */
internal fun TemplateRisk.color(): Color = when (this) {
    TemplateRisk.VERIFIED -> Color(0xFF2E7D32)
    TemplateRisk.LIKELY -> Color(0xFF1565C0)
    TemplateRisk.EXPERIMENTAL -> Color(0xFFEF6C00)
}

/** 提示文案里可用的占位符，由 UI 填入可访问地址。 */
private const val URLS_PLACEHOLDER = "{urls}"

/**
 * 提示文案。[webPorts] 只对含 `{urls}` 占位符的提示有意义：
 * proot 与手机共享网络栈、端口不映射，所以给出 `http://127.0.0.1:<port>` 形式的可访问地址。
 */
internal fun TemplateNoteInfo.text(language: String, webPorts: List<Int>): String {
    val resolved = text.resolve(language)
    if (!resolved.contains(URLS_PLACEHOLDER)) return resolved
    val urls = webPorts.joinToString(", ") { "http://127.0.0.1:$it" }
    return resolved.replace(URLS_PLACEHOLDER, urls)
}

/** 逐条列出模板提示（方案 §5.3「提示列表」）。 */
@Composable
internal fun TemplateNoteList(
    notes: List<TemplateNoteInfo>,
    language: String,
    webPorts: List<Int>,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.compose_tpl_notes_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notes.forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = note.text(language, webPorts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 端口直达：proot 不做端口映射，容器内监听端口在手机上就是同一个端口，
 * 因此可以直接交给系统浏览器打开（方案 §4.5「直接给出可点击/可复制的地址模板」）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortShortcuts(ports: List<Int>, modifier: Modifier = Modifier) {
    if (ports.isEmpty()) return
    val context = LocalContext.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ports.forEach { port ->
            val url = "http://127.0.0.1:$port"
            TextButton(onClick = { openUrl(context, url) }) {
                Text(text = url, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 风险角标 + 免费/Pro 角标的统一写法；风险标签来自模板分类学（内容侧数据），免费/Pro 是 App 自身文案。 */
@Composable
internal fun TemplateBadgeRow(
    risk: TemplateRisk,
    premium: Boolean,
    taxonomy: TemplateTaxonomy,
    language: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            label = taxonomy.riskLabel(risk).resolve(language).ifBlank { risk.id },
            containerColor = risk.color(),
        )
        StatusChip(
            label = stringResource(
                if (premium) R.string.compose_tpl_badge_pro else R.string.compose_tpl_badge_free,
            ),
            containerColor = if (premium) Color(0xFF6A1B9A) else Color(0xFF00695C),
        )
    }
}

/** 取当前 Compose 环境里的 Activity（Compose 没有直接的 Activity 入口，需逐层解包 ContextWrapper）。 */
internal fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 用系统浏览器打开链接；无可用应用时返回 false（调用方无需提示，失败即静默）。 */
internal fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: Exception) {
    false
}
