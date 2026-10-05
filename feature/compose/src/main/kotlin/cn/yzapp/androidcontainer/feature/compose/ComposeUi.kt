package cn.yzapp.androidcontainer.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.engine.compose.ComposeIssue

/**
 * 编排模块跨页面共用的小组件（列表页 / 编辑页 / 详情页都会用到）。
 */

/** 兼容性提示（方案 §3.1：不支持的键显式告知，不静默丢弃）。 */
@Composable
internal fun IssueList(issues: List<ComposeIssue>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.compose_issues),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        issues.forEach { issue ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = issue.line?.let { stringResource(R.string.compose_line, it) } ?: "·",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = issue.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 解析错误行号上下文：错误行前后各取一行，行号 + 错误行高亮（TextField 本身不给行号）。
 */
@Composable
internal fun YamlErrorSnippet(yaml: String, errorLine: Int, modifier: Modifier = Modifier) {
    val lines = yaml.lines()
    val from = (errorLine - 2).coerceAtLeast(0)
    val to = (errorLine + 1).coerceAtMost(lines.size)
    if (from >= to) return
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(8.dp)) {
            for (index in from until to) {
                val isErrorLine = index + 1 == errorLine
                Text(
                    text = "${index + 1}: ${lines[index]}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isErrorLine) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isErrorLine) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
