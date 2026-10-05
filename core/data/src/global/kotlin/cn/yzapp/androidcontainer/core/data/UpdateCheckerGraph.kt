package cn.yzapp.androidcontainer.core.data

import android.content.Context

/**
 * 服务定位入口（`global` 渠道）：与 `cn` 同名接缝。
 * Play 渠道更新走 Google Play，不做应用内检查，返回恒为「已是最新」的空实现；
 * 设置页入口由 `feature/settings` 的 `src/global` 空壳隐藏。
 */
object UpdateCheckerGraph {

    fun create(context: Context): UpdateChecker = NoopUpdateChecker
}

internal data object NoopUpdateChecker : UpdateChecker {
    override suspend fun check(): UpdateCheckResult = UpdateCheckResult.UpToDate
}
