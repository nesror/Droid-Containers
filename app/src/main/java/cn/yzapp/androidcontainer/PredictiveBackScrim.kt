package cn.yzapp.androidcontainer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 侧滑返回手势进行中的蒙版透明度：恒定浅色，不做跟手渐变。 */
private const val ScrimAlpha = 0.2f

/** 手势结束（完成 / 取消）后蒙版收尾时长。 */
private const val ScrimReleaseMillis = 150

/**
 * 侧滑返回（predictive back）过程中，给"被手势揭示的上一页"盖一层**恒定浅色蒙版**：
 * 手势进行中透明度不变（无逐帧渐变），手势结束后短动画淡出。
 *
 * 只在系统侧滑手势进行中生效——点按返回键、冷启动等非手势场景不加蒙版。
 *
 * 性能要点：只跟踪手势开始/结束两个状态沿，**手势进行中的每帧进度更新被 collect 过滤掉**
 * ——整个手势期间零状态写入、零重组、零多余重绘；透明度仍在 draw 阶段读取（drawWithContent）。
 *
 * @param isRevealed 当前内容是否为被揭示的上一页（即不是返回栈栈顶）
 * @param content 页面内容
 */
@Composable
internal fun PredictiveBackScrim(isRevealed: Boolean, content: @Composable () -> Unit) {
  val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
  var inGesture by remember { mutableStateOf(false) }
  val release = remember { Animatable(0f) }
  LaunchedEffect(dispatcher) {
    val flow = dispatcher?.transitionState ?: return@LaunchedEffect
    var releaseJob: Job? = null
    var wasInGesture = false
    flow.collect { state ->
      val active =
        state is NavigationEventTransitionState.InProgress &&
          state.direction == NavigationEventTransitionState.TRANSITIONING_BACK
      if (active != wasInGesture) {
        inGesture = active
        if (active) {
          // 重新开始手势：取消收尾动画，蒙版回到恒定值
          releaseJob?.cancel()
          releaseJob = null
        } else {
          // 手势刚结束（完成 / 取消）：浅色蒙版短动画淡出，避免生硬跳变
          releaseJob =
            launch {
              release.snapTo(ScrimAlpha)
              release.animateTo(0f, tween(durationMillis = ScrimReleaseMillis))
            }
        }
        wasInGesture = active
      }
    }
  }
  val scrimColor = MaterialTheme.colorScheme.scrim
  Box(
    modifier =
      Modifier.drawWithContent {
        drawContent()
        val alpha = if (inGesture && isRevealed) ScrimAlpha else release.value
        if (alpha > 0f) drawRect(color = scrimColor, alpha = alpha)
      }
  ) {
    content()
  }
}
