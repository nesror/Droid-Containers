package cn.yzapp.androidcontainer.feature.settings.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国内定制 ROM 后台保活工具（方案 §5 后台运行）：
 *
 * 国内厂商 ROM（MIUI/EMUI/ColorOS/OriginOS 等）普遍激进查杀后台与自启，仅靠前台服务
 * 无法完全保证容器存活，需要引导用户完成三类配置：
 * 1. 系统电池优化白名单（原生 Android 通用路径）；
 * 2. 厂商自启动权限页（各家入口不同，逐个尝试候选组件，失败回退应用详情页）；
 * 3. 最近任务锁定 / 省电策略（只能文字引导）。
 *
 * 实现注意：API 30+ 包可见性限制下 [PackageManager.resolveActivity] 对其他包组件返回 null，
 * 因此不预判可达性，直接 [Context.startActivity] 并捕获 ActivityNotFoundException 逐个回退
 * （startActivity 不受可见性过滤影响）。
 */

/** 厂商后台策略归类（displayId 为对应 ROM 名称字符串资源）。 */
enum class VendorBgPolicy {
    XIAOMI, HUAWEI, HONOR, OPPO, VIVO, MEIZU, SAMSUNG, OTHER,
}

fun detectVendorPolicy(): VendorBgPolicy {
    val maker = Build.MANUFACTURER?.lowercase().orEmpty()
    val brand = Build.BRAND?.lowercase().orEmpty()
    return when {
        "xiaomi" in maker || "redmi" in maker || "poco" in maker ||
            "xiaomi" in brand || "redmi" in brand -> VendorBgPolicy.XIAOMI
        "honor" in maker || "honor" in brand -> VendorBgPolicy.HONOR
        "huawei" in maker || "huawei" in brand -> VendorBgPolicy.HUAWEI
        "oppo" in maker || "oneplus" in maker || "realme" in maker ||
            "oneplus" in brand || "realme" in brand -> VendorBgPolicy.OPPO
        "vivo" in maker || "iqoo" in maker || "iqoo" in brand -> VendorBgPolicy.VIVO
        "meizu" in maker || "meizu" in brand -> VendorBgPolicy.MEIZU
        "samsung" in maker || "samsung" in brand -> VendorBgPolicy.SAMSUNG
        else -> VendorBgPolicy.OTHER
    }
}

/** 应用是否已在电池优化白名单中。 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * 申请电池优化白名单：弹出系统确认对话框（用户主动确认，非静默授权）。
 * 仅应用声明了 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限时可用（Manifest 已声明）。
 */
fun batteryWhitelistIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** 系统电池优化列表页（无需特殊权限的兜底入口）。 */
fun batteryOptimizationListIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** 应用详情页（最终兜底，任何设备可达）。 */
fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** 按厂商返回自启/后台设置候选 Intent 列表（按命中率排序，末尾为通用兜底）。 */
private fun vendorIntents(context: Context, vendor: VendorBgPolicy): List<Intent> {
    val pkg = context.packageName
    fun component(pkgName: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkgName, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return when (vendor) {
        VendorBgPolicy.XIAOMI -> listOf(
            // 自启动管理
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // 省电策略（应用 individually：无限制）
            Intent().setComponent(
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            ).putExtra("package_name", pkg)
                .putExtra("package_label", "Android Container")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        VendorBgPolicy.HUAWEI -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )

        VendorBgPolicy.HONOR -> listOf(
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )

        VendorBgPolicy.OPPO -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )

        VendorBgPolicy.VIVO -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )

        VendorBgPolicy.MEIZU -> listOf(
            // Flyme 应用安全页（action 形式 + 指定类名双保险）
            Intent("com.meizu.safe.security.SHOW_APPSEC")
                .setClassName("com.meizu.safe", "com.meizu.safe.security.ShowAppSecActivity")
                .putExtra("packageName", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        )

        VendorBgPolicy.SAMSUNG -> listOf(
            batteryOptimizationListIntent(),
        )

        VendorBgPolicy.OTHER -> listOf(
            batteryOptimizationListIntent(),
        )
    }
}

/**
 * 打开厂商自启/后台设置：按候选列表逐个尝试，全部失败回退应用详情页。
 * 返回 true 表示成功拉起厂商页面（false = 已回退详情页）。
 */
fun openVendorBackgroundSettings(context: Context, vendor: VendorBgPolicy): Boolean {
    for (intent in vendorIntents(context, vendor)) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // 组件不存在 / ROM 裁剪：尝试下一个候选
        }
    }
    try {
        context.startActivity(appDetailsIntent(context))
    } catch (_: Exception) {
        // 理论不可达（应用详情页必然存在）
    }
    return false
}
