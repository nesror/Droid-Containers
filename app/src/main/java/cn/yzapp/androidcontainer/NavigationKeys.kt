package cn.yzapp.androidcontainer

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 顶层导航目的地（方案 §5：仪表盘/镜像/容器/编排/设置）。 */
@Serializable data object Dashboard : NavKey

/** 首次启动后台保活引导（完成或跳过后不再自动出现，设置页可回看）。 */
@Serializable data object Onboarding : NavKey

@Serializable data object Images : NavKey

@Serializable data object Containers : NavKey

@Serializable data object ComposeProjects : NavKey

@Serializable data object Settings : NavKey

// ---- 设置二级页面（设置概览 → 具体分组，避免单页过长） ----

/** 容器设置：镜像源与 DNS。 */
@Serializable data object SettingsContainer : NavKey

/** 远程控制：Web 控制台 / Docker API / 审计日志。 */
@Serializable data object SettingsRemote : NavKey

/** 后台运行：电池优化白名单与厂商自启引导。 */
@Serializable data object SettingsKeepAlive : NavKey

/** 帮助与反馈：使用帮助 / GitHub 问题反馈 / 日志导出。 */
@Serializable data object SettingsHelp : NavKey

/** 分享应用：下载地址与系统分享。 */
@Serializable data object SettingsShare : NavKey

// ---- 编排模板库（方案 compose_template_library_and_iap_plan.md §6.3） ----

/** 编排模板库。 */
@Serializable data object ComposeTemplates : NavKey

/** 模板详情；[templateId] 对应模板目录里的稳定标识（如 `smarthome`）。 */
@Serializable data class ComposeTemplateDetail(val templateId: String) : NavKey

/** 模板包解锁（Google Play 一次性商品）。 */
@Serializable data object ProUnlock : NavKey

// ---- 列表页 → 独立二级页（新建/编辑不再内嵌在列表里） ----

/** 新建容器：docker 命令导入 + 表单填写。 */
@Serializable data object ContainerNew : NavKey

/** 容器终端（WebView + xterm.js，JS 桥直连引擎 PTY 会话）。 */
@Serializable data class ContainerTerminal(val containerId: String, val containerName: String) : NavKey

/**
 * 编排项目编辑器（新建与编辑共用一页）。
 *
 * [projectId] 为 `null` 表示新建（草稿取自模板回传或内置样例），非空表示编辑既有项目。
 * 草稿由编辑器页进入时按本参数自行准备——每页持有自己的 ViewModel，
 * 不再由列表页跨越导航条目预写共享状态。
 */
@Serializable data class ComposeProjectEditor(val projectId: String? = null) : NavKey

/**
 * 编排项目详情（独立二级页）：系统返回 = 出栈回编排列表，不再直接退出应用。
 * [projectId] 进栈时重新绑定详情状态；视图模型归本条目所有（见 Navigation 的 ViewModelStore 装饰器）。
 */
@Serializable data class ComposeProjectDetail(val projectId: String) : NavKey
