package cn.yzapp.androidcontainer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.designsystem.R

/**
 * 容器日志查看器：等宽字体、支持尾部跟随（方案 §3.4 / §5）。
 * 日志内容为每次启动重写的完整行列表，由调用方负责刷新。
 */
@Composable
fun LogViewer(
    lines: List<String>,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) {
            listState.scrollToItem(index = lines.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.ds_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
