package cn.yzapp.androidcontainer.core.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 国内分发（`cn` 渠道）的权益仓库：**恒为已解锁，不可购买**。
 *
 * 国内渠道不上 Google Play 内购，因此：
 * - Web 控制台 / REST 的解锁门禁（[EntitlementGate]）恒放行——设置页开关可直接开启 Web 服务；
 * - 收费（premium）模板由 `DistributionChannel.paidTemplatesVisible` 控制不展示，购买入口不可达；
 * - `purchase` / `restore` 返回 [BillingFailure.PlayUnavailable]，仅作兜底防御。
 */
internal class FreeEntitlementRepository : EntitlementRepository {

    private val stateFlow = MutableStateFlow<EntitlementState>(
        EntitlementState.Unlocked(EntitlementState.Unlocked.Source.BUILT_IN),
    )

    override val state: Flow<EntitlementState> = stateFlow.asStateFlow()

    override val product: Flow<ProductOffer?> = MutableStateFlow<ProductOffer?>(null).asStateFlow()

    override val playAvailability: Flow<PlayAvailability> =
        MutableStateFlow(PlayAvailability.UNAVAILABLE).asStateFlow()

    override suspend fun refresh(force: Boolean) = Unit

    override suspend fun purchase(activity: Activity): PurchaseOutcome =
        PurchaseOutcome.Failure(BillingFailure.PlayUnavailable)

    override suspend fun restore(): PurchaseOutcome =
        PurchaseOutcome.Failure(BillingFailure.PlayUnavailable)
}

/**
 * 服务定位入口（`cn` 渠道）：与 `global` 同名接缝，`core:data` 的 `DataGraph` 无需感知渠道差异。
 */
object BillingGraph {

    fun create(context: Context): EntitlementRepository = FreeEntitlementRepository()
}
