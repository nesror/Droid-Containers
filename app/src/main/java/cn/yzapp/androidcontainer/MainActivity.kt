package cn.yzapp.androidcontainer

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import cn.yzapp.androidcontainer.core.designsystem.component.LocalWindowWidth
import cn.yzapp.androidcontainer.core.designsystem.theme.AndroidContainerMasterTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    // 默认 enableEdgeToEdge 在 API 29-34 上开启导航栏对比度强制（三键导航会叠系统半透明遮罩），
    // 导致系统导航条区域与底部 tab 栏颜色不一致，这里关闭该强制对比度。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    setContent {
      AndroidContainerMasterTheme {
        // 窗口尺寸类按窗口实际宽度计算（旋转/分屏触发 Activity 重建时自动刷新），
        // 经 LocalWindowWidth 下发给各页面做宽屏布局决策
        @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
        val windowSizeClass = calculateWindowSizeClass(this)
        CompositionLocalProvider(
          LocalWindowWidth provides windowSizeClass.widthSizeClass
        ) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation()
          }
        }
      }
    }
  }
}
