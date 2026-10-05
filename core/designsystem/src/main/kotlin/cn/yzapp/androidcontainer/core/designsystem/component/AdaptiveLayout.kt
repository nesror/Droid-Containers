package cn.yzapp.androidcontainer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 当前窗口宽度尺寸类（Compact / Medium / Expanded），由 MainActivity 经
 * `calculateWindowSizeClass` 计算后向下提供。横竖屏、分屏、折叠屏展开都会随
 * 窗口宽度变化——按"窗口有多宽"而不是"设备是什么方向"做布局决策（Google 大屏适配推荐）。
 *
 * 默认 [WindowWidthSizeClass.Compact]：未提供的场景（如预览、单测）按最窄窗口处理，
 * 布局退化为与竖屏手机一致，永远安全。
 */
val LocalWindowWidth = staticCompositionLocalOf { WindowWidthSizeClass.Compact }

/** 当前窗口宽度尺寸类。 */
@Composable
fun currentWindowWidthClass(): WindowWidthSizeClass = LocalWindowWidth.current

/**
 * 内容限宽容器（Google 大屏建议：单列内容在宽窗口下限宽居中，避免一行文字横贯整个屏幕）。
 *
 * 窄窗口（手机竖屏）下 max 约束不生效，外观与限宽前完全一致，因此可以无条件包裹使用。
 * 注意：高度仍占满（fillMaxHeight），只限宽度。
 *
 * @param maxWidth 内容最大宽度；表单/设置类 600dp，顶层单列列表 840dp 左右
 */
@Composable
fun ContentMaxWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 600.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.fillMaxHeight().widthIn(max = maxWidth), content = content)
    }
}
