package cn.yzapp.androidcontainer.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Play 内购权益仓库（`global` 渠道，方案 §6.4）。
 *
 * DataStore 缓存 ⊕ 本次会话 Play 查询结果的合并视图；
 * **只有拿到明确结论才改写权益位**，避免把已付费用户误判为未解锁。
 */
internal class PlayEntitlementRepository(
    private val gateway: PlayBillingGateway,
    private val store: EntitlementStore,
) : EntitlementRepository {

    /** 本次会话的 Play 校验结论；`null` 表示本次会话尚未得出（回落 DataStore 缓存）。 */
    private val session = MutableStateFlow<Boolean?>(null)

    private val productOffer = MutableStateFlow<ProductOffer?>(null)

    private val availability = MutableStateFlow(PlayAvailability.UNKNOWN)

    private val refreshMutex = Mutex()

    @Volatile
    private var lastAttemptAt = 0L

    override val product: Flow<ProductOffer?> = productOffer.asStateFlow()

    override val playAvailability: Flow<PlayAvailability> = availability.asStateFlow()

    override val state: Flow<EntitlementState> = combine(store.proUnlocked, session) { cached, current ->
        when (current ?: cached) {
            true -> EntitlementState.Unlocked(
                if (current != null) {
                    EntitlementState.Unlocked.Source.PLAY
                } else {
                    EntitlementState.Unlocked.Source.CACHE
                },
            )

            false -> EntitlementState.Locked
            null -> EntitlementState.Unknown
        }
    }

    override suspend fun refresh(force: Boolean) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastAttemptAt < THROTTLE_MS) return
            lastAttemptAt = now
            syncWithPlay()
        }
    }

    override suspend fun purchase(activity: Activity): PurchaseOutcome {
        val connection = gateway.connect()
        if (connection.responseCode != BillingClient.BillingResponseCode.OK) {
            availability.value = connection.availabilityOf()
            return PurchaseOutcome.Failure(connection.toFailure())
        }
        availability.value = PlayAvailability.AVAILABLE

        val accountTag = store.ensureAccountTag()
        // launchBillingFlow 要求主线程且持有 Activity
        val attempt = withContext(Dispatchers.Main) {
            gateway.purchase(activity, BillingProducts.TEMPLATE_PACK, accountTag)
        }
        return when (attempt) {
            is PurchaseAttempt.Completed -> {
                store.markVerified(attempt.purchaseToken)
                session.value = true
                PurchaseOutcome.Success
            }

            is PurchaseAttempt.Rejected -> PurchaseOutcome.Failure(attempt.failure)
        }
    }

    override suspend fun restore(): PurchaseOutcome {
        val connection = gateway.connect()
        if (connection.responseCode != BillingClient.BillingResponseCode.OK) {
            availability.value = connection.availabilityOf()
            return PurchaseOutcome.Failure(connection.toFailure())
        }
        availability.value = PlayAvailability.AVAILABLE
        lastAttemptAt = System.currentTimeMillis()

        return when (val owned = gateway.queryOwnedPurchases()) {
            is OwnedQuery.Failed -> PurchaseOutcome.Failure(owned.failure)
            is OwnedQuery.Result -> {
                val purchase = owned.purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (purchase == null) {
                    store.markVerified(null)
                    session.value = false
                    PurchaseOutcome.Failure(BillingFailure.NothingToRestore)
                } else {
                    store.markVerified(purchase.purchaseToken)
                    session.value = true
                    PurchaseOutcome.Success
                }
            }
        }
    }

    /**
     * 与 Play 对账一次。**只有拿到明确结论才改写权益位**：
     * 网络抖动、Play 不可用都不落权益，避免把已付费用户误判为未解锁。
     */
    private suspend fun syncWithPlay() {
        val connection = gateway.connect()
        if (connection.responseCode != BillingClient.BillingResponseCode.OK) {
            availability.value = connection.availabilityOf()
            return
        }
        availability.value = PlayAvailability.AVAILABLE

        when (val owned = gateway.queryOwnedPurchases()) {
            is OwnedQuery.Failed -> Unit
            is OwnedQuery.Result -> {
                val ownedPurchase = owned.purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                // 补做遗漏的 acknowledge（Play 要求 3 天内确认，否则自动退款）
                owned.purchases
                    .filterNot { it.isAcknowledged }
                    .forEach { gateway.acknowledge(it.purchaseToken) }
                store.markVerified(ownedPurchase?.purchaseToken)
                session.value = ownedPurchase != null
            }
        }

        gateway.loadOffer(BillingProducts.TEMPLATE_PACK).first?.let { productOffer.value = it }
    }

    private companion object {
        /** 静默恢复查询节流：10 分钟内不重复请求。 */
        const val THROTTLE_MS = 10 * 60 * 1000L
    }
}

/** `BILLING_UNAVAILABLE` 才是「设备不可用」；断连与超时属暂时性网络问题，保持 UNKNOWN。 */
internal fun BillingResult.availabilityOf(): PlayAvailability =
    if (responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
        PlayAvailability.UNAVAILABLE
    } else {
        PlayAvailability.UNKNOWN
    }

/**
 * 服务定位入口（`global` 渠道）：`core:data` 通过它拿到 [EntitlementRepository]，无需接触 Play API。
 */
object BillingGraph {

    fun create(context: Context): EntitlementRepository {
        val appContext = context.applicationContext
        return PlayEntitlementRepository(
            gateway = PlayBillingGateway(appContext),
            store = EntitlementStore(appContext),
        )
    }
}
