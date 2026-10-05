package cn.yzapp.androidcontainer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.catch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.designsystem.component.ContentMaxWidth
import cn.yzapp.androidcontainer.core.designsystem.component.LocalWindowWidth
import cn.yzapp.androidcontainer.feature.compose.ComposeDetailScreen
import cn.yzapp.androidcontainer.feature.compose.ComposeEditorScreen
import cn.yzapp.androidcontainer.feature.compose.ComposeProjectsScreen
import cn.yzapp.androidcontainer.feature.compose.ProUnlockScreen
import cn.yzapp.androidcontainer.feature.compose.TemplateDetailScreen
import cn.yzapp.androidcontainer.feature.compose.TemplatesScreen
import cn.yzapp.androidcontainer.feature.containers.ContainersScreen
import cn.yzapp.androidcontainer.feature.containers.NewContainerScreen
import cn.yzapp.androidcontainer.feature.containers.TerminalScreen
import cn.yzapp.androidcontainer.feature.dashboard.DashboardScreen
import cn.yzapp.androidcontainer.feature.images.ImagesScreen
import cn.yzapp.androidcontainer.feature.settings.ContainerSettingsScreen
import cn.yzapp.androidcontainer.feature.settings.HelpFeedbackScreen
import cn.yzapp.androidcontainer.feature.settings.KeepAliveScreen
import cn.yzapp.androidcontainer.feature.settings.OnboardingScreen
import cn.yzapp.androidcontainer.feature.settings.RemoteControlScreen
import cn.yzapp.androidcontainer.feature.settings.SettingsScreen
import cn.yzapp.androidcontainer.feature.settings.ShareScreen

/**
 * NavigationSuiteScaffold 自适应导航：手机底部栏，平板/横屏侧栏（方案 §5）。
 */
private data class Destination(
    val key: NavKey,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val destinations =
    listOf(
        Destination(Dashboard, R.string.nav_dashboard, Icons.Filled.Home),
        Destination(Images, R.string.nav_images, Icons.Filled.Search),
        Destination(Containers, R.string.nav_containers, Icons.Filled.PlayArrow),
        Destination(ComposeProjects, R.string.nav_compose, Icons.AutoMirrored.Filled.List),
        Destination(Settings, R.string.nav_settings, Icons.Filled.Settings),
    )

@Composable
fun MainNavigation() {
  // 首次启动决策：引导未完成 → 落地 Onboarding；未加载完成前先留白（DataStore 异步读取）。
  // Flow 必须 catch：DataStore 文件损坏时 collect 会抛异常，无 catch 则永久停在空白页
  // （只能清数据恢复）——损坏按「未完成引导」兜底（P1-19）
  val onboardingDone by DataGraph.settingsRepository.onboardingCompleted
    .catch { emit(false) }
    .collectAsStateWithLifecycle(initialValue = null)
  when (onboardingDone) {
    null -> Box(modifier = Modifier.fillMaxSize())
    else -> MainNavContent(startOnDashboard = onboardingDone == true)
  }
}

@Composable
private fun MainNavContent(startOnDashboard: Boolean) {
  val backStack = rememberNavBackStack(if (startOnDashboard) Dashboard else Onboarding)
  val current = backStack.lastOrNull()
  // 入栈去重：快速连点入口时避免同一目的地叠加多次
  val push: (NavKey) -> Unit = { key -> if (backStack.lastOrNull() != key) backStack.add(key) }
  // 顶层目的地之间切换（底部 tab 与页面内的“去编排”推荐入口同语义）：清栈落位，不在二级页上继续堆叠
  val switchTop: (NavKey) -> Unit = { key ->
    if (backStack.lastOrNull() != key) {
      backStack.clear()
      backStack.add(key)
    }
  }
  // 出栈兜底（2026-09-30）：至少保留 1 个目的地。NavDisplay 重组期 require(backStack.isNotEmpty())，
  // 任何双重出栈（如编辑器页 closeEditor 触发状态回环再弹一次）都会把栈清空直接闪退——
  // 有此护栏后重复弹出退化为无害的 no-op，导航仍落在正确页面。
  // 注意：Onboarding 完成时的出栈不走这里（栈可能只有 Onboarding 一层，需替换为 Dashboard）。
  val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

  // 导航套件形态按「窗口有多宽」决定，不按设备方向：窄窗口（竖屏手机）= 底部栏，
  // 其余（手机横屏 / 平板 / 折叠展开）= 左侧栏，tab 竖排贴左边。
  // 必须显式传入 layoutType —— M3 的默认规则（calculateFromAdaptiveInfo）在窗口高度为 Compact 时
  // 一律回退底部栏，而手机横屏恰好就是「够宽但很矮」的窗口，默认规则下横屏仍停在底部。
  val suiteAsRail = LocalWindowWidth.current != WindowWidthSizeClass.Compact
  val suiteLayoutType =
    if (suiteAsRail) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar

  // 导航套件（底部 tab / 侧栏）只属于顶层目的地：进入二级页后收起，避免层级误导与误触
  val isTopLevel = destinations.any { it.key == current }
  val suiteState =
    rememberNavigationSuiteScaffoldState(
      initialValue =
        if (isTopLevel) NavigationSuiteScaffoldValue.Visible else NavigationSuiteScaffoldValue.Hidden
    )
  LaunchedEffect(isTopLevel) {
    // snapTo 让状态立即生效，显隐位移交给脚手架内部的过渡动画，
    // 比 show()/hide() 少一段"状态先动、界面后动"的等待
    suiteState.snapTo(
      if (isTopLevel) NavigationSuiteScaffoldValue.Visible else NavigationSuiteScaffoldValue.Hidden
    )
  }

  val keyToEntry: (NavKey) -> NavEntry<NavKey> =
    entryProvider {
      entry<Onboarding> {
        // 完成引导：弹栈回到来源页；冷启动首启场景栈空则落 Dashboard
        OnboardingScreen(
          onFinish = {
            backStack.removeLastOrNull()
            if (backStack.isEmpty()) backStack.add(Dashboard)
          },
        )
      }
      entry<Dashboard> { DashboardScreen() }
      entry<Images> {
        // 镜像页的“让编排帮你拉取镜像”推荐入口：直接切到编排顶层页
        ImagesScreen(onOpenCompose = { switchTop(ComposeProjects) })
      }
      entry<Containers> {
        ContainersScreen(
          onNewContainer = { push(ContainerNew) },
          onOpenCompose = { switchTop(ComposeProjects) },
          onOpenTerminal = { id, name -> push(ContainerTerminal(id, name)) },
        )
      }
      // 新建容器：表单整体搬到独立二级页，返回即出栈
      entry<ContainerNew> {
        NewContainerScreen(onDone = { pop() })
      }
      // 容器终端：独立 proot shell 会话 + 真 PTY，返回即关闭会话
      entry<ContainerTerminal> { route ->
        TerminalScreen(
          containerId = route.containerId,
          containerName = route.containerName,
          onBack = { pop() },
        )
      }
      entry<ComposeProjects> {
        ComposeProjectsScreen(
          onOpenTemplates = { push(ComposeTemplates) },
          // null = 新建，非空 = 编辑该项目（草稿由编辑器页自行准备）
          onOpenEditor = { projectId -> push(ComposeProjectEditor(projectId)) },
          onOpenDetail = { projectId -> push(ComposeProjectDetail(projectId)) },
        )
      }
      // 编排项目详情：独立二级页，系统返回 = 出栈回列表（不再直接退出应用）
      entry<ComposeProjectDetail> { route ->
        ComposeDetailScreen(
          projectId = route.projectId,
          onBack = { pop() },
          onEdit = { push(ComposeProjectEditor(route.projectId)) },
        )
      }
      // 编排编辑器：projectId 随 NavKey 进来，本页据此准备草稿并提交
      entry<ComposeProjectEditor> { route ->
        ComposeEditorScreen(
          projectId = route.projectId,
          onDone = { pop() },
        )
      }
      entry<ComposeTemplates> {
        TemplatesScreen(
          onBack = { pop() },
          onOpenTemplate = { templateId -> push(ComposeTemplateDetail(templateId)) },
          onOpenUnlock = { push(ProUnlock) },
        )
      }
      entry<ComposeTemplateDetail> { route ->
        TemplateDetailScreen(
          templateId = route.templateId,
          onBack = { pop() },
          onOpenUnlock = { push(ProUnlock) },
          onUseTemplate = {
            // 模板正文已写入 ComposeDraftBus（YAML 不进 NavKey，否则会进序列化与日志）：
            // 先退回编排列表，再进编辑器（返回时落在列表页而不是模板详情），草稿由编辑器页消费
            while (backStack.size > 1 && backStack.last() !is ComposeProjects) {
              backStack.removeLastOrNull()
            }
            push(ComposeProjectEditor(projectId = null))
          },
        )
      }
      entry<ProUnlock> {
        ProUnlockScreen(onBack = { pop() })
      }
      entry<Settings> {
        SettingsScreen(
          onOpenContainerSettings = { push(SettingsContainer) },
          onOpenRemoteControl = { push(SettingsRemote) },
          onOpenKeepAlive = { push(SettingsKeepAlive) },
          onOpenHelp = { push(SettingsHelp) },
          onOpenShare = { push(SettingsShare) },
        )
      }
      // 设置二级页面：返回 = 出栈回到设置概览
      entry<SettingsContainer> {
        ContainerSettingsScreen(onBack = { pop() })
      }
      entry<SettingsRemote> {
        RemoteControlScreen(
          onBack = { pop() },
          onOpenUnlock = { push(ProUnlock) },
        )
      }
      entry<SettingsKeepAlive> {
        KeepAliveScreen(
          onBack = { pop() },
          onReplayOnboarding = { push(Onboarding) },
        )
      }
      entry<SettingsHelp> {
        HelpFeedbackScreen(onBack = { pop() })
      }
      entry<SettingsShare> {
        ShareScreen(onBack = { pop() })
      }
    }

  NavigationSuiteScaffold(
    navigationSuiteItems = {
      destinations.forEach { dest ->
        item(
          selected = current == dest.key,
          onClick = { switchTop(dest.key) },
          icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
          label = { Text(stringResource(dest.labelRes)) },
        )
      }
    },
    layoutType = suiteLayoutType,
    state = suiteState,
  ) {
    // insets 全部由各 entry 自管（2026-09-22 起）：二级页脚手架自行消费系统栏并占满全屏，
    // 顶层页由下面的 wrapper 避让——侧滑返回过程中页面不再露出上下两条窗口背景色。
    Box(modifier = Modifier.fillMaxSize()) {
      NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        modifier = Modifier.fillMaxSize(),
        // entry 级 ViewModelStore（审查 P1-11）：navigation3 默认只装 SaveableStateHolder，
        // 没有 ViewModelStore 装饰器 → 所有页面的 `viewModel()` 都落到 Activity 的 Store，
        // ViewModel 永不 onCleared（设置页 5 条常驻 collect、各页日志轮询活到进程结束）。
        // 补上后条目出栈即 clear（onCleared → viewModelScope 取消）；仍在返回栈中的条目
        // 保留各自实例，返回时不丢状态。长任务已改挂 DataGraph.appScope，不受此影响。
        entryDecorators = listOf<NavEntryDecorator<NavKey>>(
          rememberSaveableStateHolderNavEntryDecorator(),
          rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key ->
          val entry = keyToEntry(key)
          // 顶层目的地：导航套件可见时只需避让顶部状态栏（底部由套件占位）；
          // 被侧滑手势揭示时套件已收起，需自行避让底部导航条。二级页/引导页自管 insets。
          val isTopKey = destinations.any { it.key == key }
          val isRevealed = backStack.lastOrNull() != key
          // 统一包一层：侧滑返回手势中被揭示的上一页（非栈顶）跟手蒙版，栈顶页不受影响
          NavEntry(navEntry = entry) {
            PredictiveBackScrim(isRevealed = isRevealed) {
              if (isTopKey) {
                // 顶层页的避让来源随套件形态变化：
                // - 底部栏：套件占住底部，页面只避顶部状态栏与横向两侧（被侧滑揭示时套件收起，补避底部）
                // - 左侧栏：套件占住左侧、且已消费 Start 侧 insets，页面要自行避顶部与底部两条系统栏；
                //   这里刻意用 systemBars + displayCutout 而非 safeDrawing —— 后者含 ime，横屏弹出键盘时
                //   内容会被「窗口重排 + ime 内缩」双重挤压，页面直接被压扁
                val topInsets =
                  when {
                    suiteAsRail ->
                      WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal)
                    isRevealed ->
                      WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal
                      )
                    else ->
                      WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                      )
                  }
                Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(topInsets)) {
                  // 顶层页均为单列列表：宽窗口下限宽居中，横屏不再整行拉满
                  ContentMaxWidth(maxWidth = 840.dp) { entry.Content() }
                }
              } else {
                entry.Content()
              }
            }
          }
        },
      )
    }
  }
}
