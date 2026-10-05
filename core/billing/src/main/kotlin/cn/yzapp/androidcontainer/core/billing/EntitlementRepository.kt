package cn.yzapp.androidcontainer.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * 权益仓库——`feature` 层对外的唯一入口（方案 §6.4）。
 *
 * 门控点只有一个：模板「是否可进入使用流程」，读取 [state]。
 * [EntitlementState.Unknown] **不等于** [EntitlementState.Locked]，调用方不得据此阻拦预览。
 *
 * 具体实现按渠道 flavor 切换（[BillingGraph] 是唯一接缝）：
 * - `global`：Google Play 内购（[PlayEntitlementRepository]）；
 * - `cn`：国内分发无 Play 内购，恒为已解锁（见 `cn` 源集），Web 控制台等门禁随之放行。
 */
interface EntitlementRepository {

    /** 渠道缓存 ⊕ 本次会话查询结果的合并视图（实现自定语义）。 */
    val state: Flow<EntitlementState>

    /** 商品名称与本地化价格；加载中为 `null`。 */
    val product: Flow<ProductOffer?>

    /** Play 服务可用性，用于解锁页的降级说明。 */
    val playAvailability: Flow<PlayAvailability>

    /**
     * 静默恢复查询：启动时与进入模板库时各一次。
     * [force] 为 `false` 时按 10 分钟节流，避免反复请求。
     */
    suspend fun refresh(force: Boolean = false)

    /** 发起购买（需持有 Activity，内部切主线程）。 */
    suspend fun purchase(activity: Activity): PurchaseOutcome

    /** 恢复购买：命中已拥有商品即视为解锁。 */
    suspend fun restore(): PurchaseOutcome
}
