package cn.yzapp.androidcontainer.feature.containers

import android.app.Activity
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 终端页 ViewModel：跨旋转持有 TerminalBridge（会话与 WebView 解耦）。
 * navigation3 的 entry 共用 Activity VM Store，按 containerId 加 key 隔离多容器。
 */
class TerminalViewModel(app: android.app.Application) : AndroidViewModel(app) {

    var bridge: TerminalBridge? = null
        private set

    fun obtain(containerId: String): TerminalBridge =
        bridge ?: TerminalBridge(containerId).also { bridge = it }

    /** 页面离开时主动关会话（VM 挂在 Activity Store 上，不随页面出栈清理）。 */
    fun destroyBridge() {
        bridge?.destroy()
        bridge = null
    }

    override fun onCleared() {
        destroyBridge()
    }
}

/**
 * 容器终端页：WebView + 内置 xterm.js（assets/terminal/），JS 桥直连引擎 PTY 会话。
 *
 * 体验要点：真 PTY（回显/行编辑/颜色/Ctrl-C）、软键盘弹出时终端跟随（imePadding）、
 * 画布下方常驻扩展按键栏（ESC/方向键/CTRL/ALT/FN 等，见 TerminalExtraKeysBar）、
 * 打开期间屏幕常亮；返回即关闭会话（proot 进程 SIGKILL 收尸）。
 * 不用 SecondaryScaffold：终端需要全幅深色画布（顶部栏 + 终端区无 16dp 内容边距）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    containerId: String,
    containerName: String,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = viewModel(key = "terminal-$containerId"),
) {
    val bridge = remember(containerId) { viewModel.obtain(containerId) }
    val context = LocalContext.current
    val darkBackground = Color(0xFF0D1117)

    // 终端使用场景用户在持续输入：屏幕常亮，页面关闭即恢复；离开页面 = 关闭终端会话
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.destroyBridge()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = darkBackground,
        // 底部只避让一次：safeDrawing 对每边取各来源最大值——键盘弹出时 bottom = IME 高
        // （键盘自带导航条区域），收起时 = 导航条高。不要再叠加 imePadding：
        //  - 双扣 IME → 键盘弹出时画布被压成一行（2026-09-24）
        //  - navigationBars + imePadding → 键盘弹出时多出一个导航条高度（2026-09-24）
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        ),
        topBar = {
            TopAppBar(
                title = { Text(containerName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                cn.yzapp.androidcontainer.core.designsystem.R.string.ds_back,
                            ),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // 终端画布占满剩余空间，扩展按键栏贴在画布与软键盘/导航条之间
        // （键盘避让已由 Scaffold 的 innerPadding 完成，此处不再 imePadding）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        isFocusableInTouchMode = true
                        setBackgroundColor(darkBackground.toArgb())
                        bridge.attach(this)
                        addJavascriptInterface(bridge, "AndroidTerm")
                        loadUrl("file:///android_asset/terminal/index.html")
                    }
                },
                onRelease = { view ->
                    // 释放时销毁 WebView（审查 P1-18）：仅 stopLoading + removeView 不会释放
                    // native 资源，每次进终端页 / 旋转都会泄漏一个 WebView 实例
                    view.stopLoading()
                    view.removeJavascriptInterface("AndroidTerm")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                },
            )
            TerminalExtraKeysBar(
                onKey = { seq ->
                    // 复用 JS 桥的 base64 通道：转义序列含 ESC 等控制字节，base64 转运最稳
                    val b64 = Base64.encodeToString(seq.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    bridge.writeB64(b64)
                },
            )
        }
    }
}
