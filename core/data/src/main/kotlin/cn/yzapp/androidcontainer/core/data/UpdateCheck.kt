package cn.yzapp.androidcontainer.core.data

/**
 * 应用更新检查（渠道接缝，与 [cn.yzapp.androidcontainer.core.billing.BillingGraph] 同一模式）：
 * `cn` 源集提供 GitHub Releases 实现，`global` 源集为空壳（设置页不展示入口）。
 */
interface UpdateChecker {
    suspend fun check(): UpdateCheckResult
}

/** 检查更新结果。错误消息为英文技术串（engine/data 层约定），由 UI 层映射文案。 */
sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val tagName: String,
        val versionName: String,
        val changelog: String,
        val pageUrl: String,
    ) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    data class Failed(val reason: String) : UpdateCheckResult
}
