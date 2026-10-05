package cn.yzapp.androidcontainer.feature.containers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 终端扩展按键栏配色（与终端画布 0xFF0D1117 同一 GitHub Dark 色系）。 */
private val BarBackground = Color(0xFF161B22)
private val DividerColor = Color(0xFF30363D)
private val KeyForeground = Color(0xFFC9D1D9)
private val ActiveBackground = Color(0xFF1F6FEB)
private val ActiveForeground = Color.White

private const val ESC = "\u001B"

/** 按键定义：普通键发转义序列；修饰键/键盘键有独立行为。 */
private sealed interface ExtraKey {
    val label: String

    /**
     * @param seq     基础序列（未叠加修饰键）
     * @param ctrlSeq CTRL 粘滞时的替代序列（如 Ctrl+→ 跳词）
     */
    data class Sequence(override val label: String, val seq: String, val ctrlSeq: String? = null) : ExtraKey
    data object Ctrl : ExtraKey { override val label = "CTRL" }
    data object Alt : ExtraKey { override val label = "ALT" }
    data object Fn : ExtraKey { override val label = "FN" }
    data object KeyboardToggle : ExtraKey { override val label = "" }
}

// 转义序列与 xterm.js 默认键盘编码对齐：方向键 CSI A/B/C/D，
// Ctrl+方向为 CSI 1;5x（shell 里跳词），翻页 CSI 5~/6~，Home/End CSI H/F。
private val ROW_1 = listOf(
    ExtraKey.Sequence("ESC", ESC),
    ExtraKey.Sequence("/", "/"),
    ExtraKey.Sequence("|", "|"),
    ExtraKey.Sequence("-", "-"),
    ExtraKey.Sequence("HOME", "${ESC}[H"),
    ExtraKey.Sequence("↑", "${ESC}[A", "${ESC}[1;5A"),
    ExtraKey.Sequence("END", "${ESC}[F"),
    ExtraKey.Sequence("PGUP", "${ESC}[5~"),
    ExtraKey.Fn,
)

private val ROW_2 = listOf(
    ExtraKey.Sequence("TAB", "\t"),
    ExtraKey.Ctrl,
    ExtraKey.Alt,
    ExtraKey.Sequence("←", "${ESC}[D", "${ESC}[1;5D"),
    ExtraKey.Sequence("↓", "${ESC}[B", "${ESC}[1;5B"),
    ExtraKey.Sequence("→", "${ESC}[C", "${ESC}[1;5C"),
    ExtraKey.Sequence("PGDN", "${ESC}[6~"),
    ExtraKey.KeyboardToggle,
)

// xterm 功能键编码：F1-F4 为 SS3（ESC O P..S），F5 起为 CSI n~
private val FN_KEYS = listOf(
    "F1" to "${ESC}OP", "F2" to "${ESC}OQ", "F3" to "${ESC}OR", "F4" to "${ESC}OS",
    "F5" to "${ESC}[15~", "F6" to "${ESC}[17~", "F7" to "${ESC}[18~", "F8" to "${ESC}[19~",
    "F9" to "${ESC}[20~", "F10" to "${ESC}[21~", "F11" to "${ESC}[23~", "F12" to "${ESC}[24~",
).map { ExtraKey.Sequence(it.first, it.second) }

// Material Icons "keyboard" 公共路径数据（24x24 viewport）
private const val KEYBOARD_ICON_PATH =
    "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2z" +
        "m-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2z" +
        "m9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"

private val keyboardIcon: ImageVector by lazy {
    val nodes = PathParser().parsePathString(KEYBOARD_ICON_PATH).toNodes()
    ImageVector.Builder(
        name = "TerminalKeyboardToggle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = nodes,
        pathFillType = PathFillType.NonZero,
        fill = SolidColor(Color.White),
    ).build()
}

/**
 * 终端扩展按键栏（终端画布与软键盘之间）：
 * - 普通按键直接把转义序列写入 PTY（等价物理键盘），不依赖 WebView 焦点；
 * - CTRL/ALT 为粘滞修饰键：点亮后作用于下一次按键，用后即清（再点一次取消）；
 * - FN 点亮展开 F1–F12 行；
 * - 最右侧按钮切换软键盘。
 * 按键文案是键帽符号（ESC/TAB/箭头等），属键盘术语，不随语言翻译。
 */
@Composable
fun TerminalExtraKeysBar(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var fnActive by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboardToggleDesc = stringResource(R.string.term_keys_toggle_keyboard)

    fun press(key: ExtraKey) {
        when (key) {
            is ExtraKey.Sequence -> {
                val base = if (ctrlActive) key.ctrlSeq ?: key.seq else key.seq
                val seq = if (altActive) ESC + base else base
                onKey(seq)
                ctrlActive = false
                altActive = false
            }
            ExtraKey.Ctrl -> ctrlActive = !ctrlActive
            ExtraKey.Alt -> altActive = !altActive
            ExtraKey.Fn -> fnActive = !fnActive
            ExtraKey.KeyboardToggle -> if (imeVisible) keyboard?.hide() else keyboard?.show()
        }
    }

    fun isActive(key: ExtraKey): Boolean = when (key) {
        ExtraKey.Ctrl -> ctrlActive
        ExtraKey.Alt -> altActive
        ExtraKey.Fn -> fnActive
        else -> false
    }

    Column(modifier = modifier.background(BarBackground)) {
        HorizontalDivider(thickness = 1.dp, color = DividerColor)
        AnimatedVisibility(visible = fnActive) {
            KeyRow(keys = FN_KEYS, isActive = ::isActive, onPress = ::press)
        }
        KeyRow(keys = ROW_1, isActive = ::isActive, onPress = ::press)
        KeyRow(keys = ROW_2, isActive = ::isActive, onPress = ::press)
    }
}

@Composable
private fun KeyRow(
    keys: List<ExtraKey>,
    isActive: (ExtraKey) -> Boolean,
    onPress: (ExtraKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        keys.forEach { key ->
            KeyButton(
                key = key,
                active = isActive(key),
                contentDescription = key.label.ifEmpty { null },
                onPress = { onPress(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeyButton(
    key: ExtraKey,
    active: Boolean,
    contentDescription: String?,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (active) ActiveBackground else Color.Transparent
    val foreground = if (active) ActiveForeground else KeyForeground
    val shape = RoundedCornerShape(6.dp)
    val described = modifier
        .clip(shape)
        .background(background)
        .clickable(onClick = onPress, onClickLabel = contentDescription)

    when (key) {
        is ExtraKey.Sequence -> Box(described, contentAlignment = Alignment.Center) {
            Text(
                text = key.label,
                color = foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        is ExtraKey.KeyboardToggle -> Box(described, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = keyboardIcon,
                contentDescription = stringResource(R.string.term_keys_toggle_keyboard),
                tint = foreground,
            )
        }
        else -> Box(described, contentAlignment = Alignment.Center) {
            Text(
                text = key.label,
                color = foreground,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
