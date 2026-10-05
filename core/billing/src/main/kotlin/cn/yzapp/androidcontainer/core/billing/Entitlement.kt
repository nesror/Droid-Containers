package cn.yzapp.androidcontainer.core.billing

/**
 * 权益状态机（方案 §4.4）。**`Unknown` 不得当作 `Locked`**：
 * 网络抖动或 Play 未就绪时会短暂处于 `Unknown`，此时 UI 应放行「预览」，只把「使用」按钮换成解锁入口。
 */
sealed interface EntitlementState {

    /** 尚未得出任何结论（首次查询进行中 / Play 暂不可用且本地无缓存）。 */
    data object Unknown : EntitlementState

    /** 确认未购买（Play 查询命中「无购买」，或退款后被静默查询回落）。 */
    data object Locked : EntitlementState

    /** 已解锁。 */
    data class Unlocked(val source: Source) : EntitlementState {

        enum class Source {
            /** 本次会话与 Play 校验确认。 */
            PLAY,

            /** 仅凭本地缓存（离线可用：买过就能一直用）。 */
            CACHE,

            /** 渠道内置解锁（`cn` 渠道免购，非用户购买所得）。 */
            BUILT_IN,
        }
    }
}

/** Play 服务可用性，用于解锁页的降级文案（方案 §6.5）。 */
enum class PlayAvailability {
    /** 尚未判断。 */
    UNKNOWN,

    /** 可用。 */
    AVAILABLE,

    /** 设备无 Play / 无 GMS，无法在此完成购买。 */
    UNAVAILABLE,
}

/** 错误码 → 文案映射的中间层（方案 §6.4）。 */
sealed interface BillingFailure {

    /** `BILLING_UNAVAILABLE`：设备未安装或无法使用 Play。 */
    data object PlayUnavailable : BillingFailure

    /** `SERVICE_DISCONNECTED` / `SERVICE_UNAVAILABLE` / `NETWORK_ERROR`。 */
    data object NetworkUnavailable : BillingFailure

    /** `ITEM_UNAVAILABLE`：账号当前不可购买该商品。 */
    data object ItemUnavailable : BillingFailure

    /** `ITEM_ALREADY_OWNED`：已购买，应引导点「恢复购买」。 */
    data object AlreadyOwned : BillingFailure

    /** `USER_CANCELED`：中性提示，不指责用户。 */
    data object Cancelled : BillingFailure

    /** 恢复购买时未找到可恢复的购买（例如账号不一致）。 */
    data object NothingToRestore : BillingFailure

    /** 其它错误（`FEATURE_NOT_SUPPORTED` / `DEVELOPER_ERROR` / `ERROR`），附 `debugMessage` 仅作诊断。 */
    data class Unexpected(val debugMessage: String?) : BillingFailure
}

/** 购买 / 恢复的结果。 */
sealed interface PurchaseOutcome {

    data object Success : PurchaseOutcome

    data class Failure(val failure: BillingFailure) : PurchaseOutcome
}

/** 商品报价（加载中为 `null`）。 */
data class ProductOffer(
    val title: String,
    val formattedPrice: String,
)
