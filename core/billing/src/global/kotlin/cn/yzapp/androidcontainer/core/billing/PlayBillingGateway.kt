package cn.yzapp.androidcontainer.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 购买尝试的结果。 */
internal sealed interface PurchaseAttempt {

    /** Play 已确认购买且 acknowledge 成功。 */
    data class Completed(val purchaseToken: String) : PurchaseAttempt

    data class Rejected(val failure: BillingFailure) : PurchaseAttempt
}

/** [PlayBillingGateway.queryOwnedPurchases] 的结果。 */
internal sealed interface OwnedQuery {

    data class Result(val purchases: List<Purchase>) : OwnedQuery

    data class Failed(val failure: BillingFailure) : OwnedQuery
}

/**
 * `BillingClient` 封装——**全工程唯一接触 Google Play API 的类**（方案 §6.4）。
 *
 * 关键实现点：
 * - `enableAutoServiceReconnection()`（PBL 8+ 内置重连）+ 显式 `enablePendingPurchases`（PBL 9 已移除无参重载）；
 * - 商品查询用 `queryProductDetailsAsync` + `ProductType.INAPP`；
 * - 发起购买必须带 `offerToken`（一次性商品在 PBL 8+ 同样支持多购买选项，漏传是最常见的集成 bug）；
 * - `onPurchasesUpdated` 返回 OK 且状态为 PURCHASED 时**必须** `acknowledgePurchase`，否则 Play 3 天后自动退款。
 *
 * 所有异步回调统一经 `CompletableDeferred` / `Channel` 收敛为 suspend 调用，不向调用方暴露回调。
 */
internal class PlayBillingGateway(context: Context) {

    private val appContext = context.applicationContext
    private val connectMutex = Mutex()

    @Volatile
    private var client: BillingClient? = null

    /** `launchBillingFlow` 的异步回执通道（同刻只允许一笔在途购买）。 */
    private val purchaseUpdates = Channel<Pair<BillingResult, List<Purchase>>>(Channel.CONFLATED)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        purchaseUpdates.trySend(result to purchases.orEmpty())
    }

    private fun requireClient(): BillingClient? {
        client?.let { return it }
        return synchronized(this) {
            client ?: BillingClient.newBuilder(appContext)
                .setListener(purchasesUpdatedListener)
                .enableAutoServiceReconnection()
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
                )
                .build()
                .also { client = it }
        }
    }

    /** 连接 Play；幂等，已连接时直接返回 OK。 */
    suspend fun connect(): BillingResult = connectMutex.withLock {
        val billingClient = requireClient() ?: return@withLock response(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        if (billingClient.isReady) return@withLock response(BillingClient.BillingResponseCode.OK)

        val deferred = CompletableDeferred<BillingResult>()
        billingClient.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    deferred.complete(result)
                }

                override fun onBillingServiceDisconnected() {
                    // enableAutoServiceReconnection() 会自行重连；这里只负责唤醒等待方
                    deferred.complete(response(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED))
                }
            },
        )
        deferred.await()
    }

    /**
     * 查询商品报价（名称 + 本地化价格）。`ProductDetails` 不越过本类边界，
     * 保证 Play API 只在此文件中出现。
     */
    suspend fun loadOffer(productId: String): Pair<ProductOffer?, BillingFailure?> {
        val (details, failure) = queryProduct(productId)
        return details?.toOffer() to failure
    }

    /** 查询一次性商品详情；返回 `(商品, 失败原因)`，二者互斥。 */
    private suspend fun queryProduct(productId: String): Pair<ProductDetails?, BillingFailure?> {
        val billingClient = requireClient() ?: return null to BillingFailure.PlayUnavailable
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val deferred = CompletableDeferred<Pair<BillingResult, List<ProductDetails>>>()
        billingClient.queryProductDetailsAsync(
            params,
            // PBL 9 的回调第二参数是 QueryProductDetailsResult，不再是裸 List
            ProductDetailsResponseListener { result, queryResult ->
                deferred.complete(result to queryResult.productDetailsList)
            },
        )
        val (result, details) = deferred.await()
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            return null to result.toFailure()
        }
        return details.firstOrNull()?.let { it to null } ?: (null to BillingFailure.ItemUnavailable)
    }

    /**
     * 静默恢复：查询当前账号已拥有的商品。
     * 这同时是**退款 / 撤销的唯一本地感知手段**——购买消失即权益失效。
     */
    suspend fun queryOwnedPurchases(): OwnedQuery {
        val billingClient = requireClient() ?: return OwnedQuery.Failed(BillingFailure.PlayUnavailable)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val deferred = CompletableDeferred<Pair<BillingResult, List<Purchase>>>()
        billingClient.queryPurchasesAsync(
            params,
            PurchasesResponseListener { result, purchases ->
                deferred.complete(result to purchases)
            },
        )
        val (result, purchases) = deferred.await()
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            OwnedQuery.Result(purchases)
        } else {
            OwnedQuery.Failed(result.toFailure())
        }
    }

    /**
     * 发起购买并等待回执。调用方需保证在主线程（`launchBillingFlow` 要求持有 Activity 且在主线程执行）。
     * 成功路径已在内部完成 acknowledge，避免 Play 3 天后自动退款。
     */
    suspend fun purchase(activity: Activity, productId: String, accountTag: String): PurchaseAttempt {
        val billingClient = requireClient() ?: return PurchaseAttempt.Rejected(BillingFailure.PlayUnavailable)
        val (product, failure) = queryProduct(productId)
        if (product == null) {
            return PurchaseAttempt.Rejected(failure ?: BillingFailure.ItemUnavailable)
        }
        val offerToken = product.oneTimePurchaseOfferToken()
            ?: return PurchaseAttempt.Rejected(BillingFailure.ItemUnavailable)

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build(),
                ),
            )
            .setObfuscatedAccountId(accountTag)
            .build()

        drainPendingUpdates()
        val launch = billingClient.launchBillingFlow(activity, params)
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            return PurchaseAttempt.Rejected(launch.toFailure())
        }

        val update = withTimeoutOrNull(PURCHASE_TIMEOUT_MS) { purchaseUpdates.receive() }
            ?: return PurchaseAttempt.Rejected(BillingFailure.Unexpected("purchase result timed out"))
        val (result, purchases) = update
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            return PurchaseAttempt.Rejected(result.toFailure())
        }
        val purchase = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            ?: return PurchaseAttempt.Rejected(BillingFailure.Unexpected(result.debugMessage))

        if (!purchase.isAcknowledged) {
            val acknowledged = acknowledge(purchase.purchaseToken)
            if (acknowledged.responseCode != BillingClient.BillingResponseCode.OK) {
                return PurchaseAttempt.Rejected(acknowledged.toFailure())
            }
        }
        return PurchaseAttempt.Completed(purchase.purchaseToken)
    }

    /** 确认购买（Play 要求 3 天内确认，否则自动退款）。 */
    suspend fun acknowledge(purchaseToken: String): BillingResult {
        val billingClient = requireClient() ?: return response(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        val deferred = CompletableDeferred<BillingResult>()
        billingClient.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
            AcknowledgePurchaseResponseListener { result -> deferred.complete(result) },
        )
        return deferred.await()
    }

    /** 并发起购买前先清掉上一笔可能残留的回执（CONFLATED 通道只保留最新一条）。 */
    fun drainPendingUpdates() {
        while (purchaseUpdates.tryReceive().isSuccess) {
            // 丢弃陈旧回执
        }
    }

    private fun response(code: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(code).build()

    private companion object {
        /** 购买流程含用户交互，给足超时；超时按未知错误处理，不写权益位。 */
        const val PURCHASE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/**
 * 取一次性商品的 offerToken。
 * 优先用 PBL 8+ 的 `oneTimePurchaseOfferDetailsList`，回退到单数形式。
 */
private fun ProductDetails.oneTimePurchaseOfferToken(): String? {
    val fromList = oneTimePurchaseOfferDetailsList
        ?.firstOrNull()
        ?.offerToken
        ?.takeIf { it.isNotBlank() }
    if (fromList != null) return fromList
    @Suppress("DEPRECATION")
    return oneTimePurchaseOfferDetails?.offerToken?.takeIf { it.isNotBlank() }
}

/** 商品报价（名称取 Play 后台的本地化商品名）。 */
private fun ProductDetails.toOffer(): ProductOffer = ProductOffer(
    title = name,
    formattedPrice = oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        ?: oneTimePurchaseOfferDetails?.formattedPrice.orEmpty(),
)

/** Play 响应码 → [BillingFailure]（方案 §6.4 映射表）。 */
internal fun BillingResult.toFailure(): BillingFailure = when (responseCode) {
    BillingClient.BillingResponseCode.USER_CANCELED -> BillingFailure.Cancelled
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingClient.BillingResponseCode.NETWORK_ERROR,
    -> BillingFailure.NetworkUnavailable

    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> BillingFailure.PlayUnavailable
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingFailure.ItemUnavailable
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingFailure.AlreadyOwned
    else -> BillingFailure.Unexpected(debugMessage)
}
