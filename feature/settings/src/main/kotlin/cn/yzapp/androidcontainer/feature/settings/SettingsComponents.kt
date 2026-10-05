package cn.yzapp.androidcontainer.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import cn.yzapp.androidcontainer.core.designsystem.component.SecondaryScaffold

/**
 * 官方站点与反馈渠道（技术常量，不作为 UI 文案做资源化）。
 */
internal object AppLinks {
    /** 下载页 / 官网首页。 */
    const val DOWNLOAD = "https://191005.xyz/container/"

    /** 使用帮助（文档、教程、常见问题）。 */
    const val HELP = "https://191005.xyz/container/help/"

    /** 问题反馈（GitHub Issues）。 */
    const val ISSUES = "https://github.com/nesror/Droid-Containers/issues"
}

/** 应用名称与版本（构建未开启 BuildConfig，统一从 PackageManager 读取，名称自动跟随应用 label 本地化）。 */
internal data class AppInfo(
    val name: String,
    val versionName: String,
    val versionCode: Long,
)

internal fun loadAppInfo(context: Context): AppInfo {
    val pm = context.packageManager
    val name = context.applicationInfo.loadLabel(pm).toString()
    return try {
        val info = pm.getPackageInfo(context.packageName, 0)
        AppInfo(
            name = name,
            versionName = info.versionName ?: "",
            versionCode = PackageInfoCompat.getLongVersionCode(info),
        )
    } catch (e: Exception) {
        AppInfo(name = name, versionName = "", versionCode = 0L)
    }
}

/** 用系统浏览器打开链接；无可用应用时返回 false（由调用方提示）。 */
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

/**
 * 设置二级页外壳：返回箭头 + 标题 + 可滚动内容。
 *
 * insets 约定见 [SecondaryScaffold]：外层导航脚手架已统一避让系统栏，这里不再消费任何 inset，
 * 避免出现双倍留白。
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SecondaryScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier,
        backContentDescription = stringResource(R.string.settings_back),
        content = content,
    )
}

/** 概览页分组：小标题 + 卡片容器（组内行用 HorizontalDivider 分隔）。 */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** 可点击行：标题 + 可选副标题 + 可选尾部摘要 + 右侧箭头。 */
@Composable
internal fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 120.dp)
                    .padding(end = 6.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关行：标题 + 可选副标题 + 尾部 Switch。`enabled=false` 时整行灰显（如远程控制的解锁门禁）。 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
