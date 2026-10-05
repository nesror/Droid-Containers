package cn.yzapp.androidcontainer.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = Blue80,
        secondary = BlueGrey80,
        tertiary = Teal80,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Blue40,
        secondary = BlueGrey40,
        tertiary = Teal40,
    )

/**
 * Material 3 主题：Android 12+ 使用 Material You 动态色，低版本用品牌色兜底（方案 §5）。
 */
@Composable
fun AndroidContainerMasterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
