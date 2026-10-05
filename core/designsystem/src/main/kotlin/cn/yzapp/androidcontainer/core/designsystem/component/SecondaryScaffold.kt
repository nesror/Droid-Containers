package cn.yzapp.androidcontainer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.designsystem.R

/**
 * 二级页统一外壳：返回箭头 + 标题 + 可滚动内容。
 *
 * **insets 约定（2026-09-22 起改为自管 edge-to-edge）**：二级页在导航容器内不再被外层统一内缩，
 * 而是占满全屏、由本组件自行消费系统栏——[TopAppBar] 用默认 windowInsets 把标题栏延伸到状态栏
 * 之下（背景色填充状态栏区域），内容经 [Scaffold] 的 contentWindowInsets 避让底部系统导航条。
 * 这样侧滑返回（predictive back）过程中页面始终占满全屏，不会露出上下两条窗口背景色。
 * 水平方向维持 0（与历史外观一致，避免横屏刘海二次内缩）。
 *
 * @param title 标题（已本地化文本）
 * @param onBack 返回回调
 * @param modifier 修饰符
 * @param backContentDescription 返回按钮的无障碍描述
 * @param actions 标题栏右侧操作（如保存）
 * @param content 页面内容，已自带纵向滚动与水平边距
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String = stringResource(R.string.ds_back),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 水平 + 底部避让：底部系统导航条常规避让；水平侧覆盖横屏刘海（shortEdges 下窗口会延伸进刘海区）
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        ),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backContentDescription,
                        )
                    }
                },
                actions = actions,
            )
        },
    ) { innerPadding ->
        // 宽窗口下单列内容限宽居中（手机竖屏不受影响）
        ContentMaxWidth(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}
