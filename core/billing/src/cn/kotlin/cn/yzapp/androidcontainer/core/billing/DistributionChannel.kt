package cn.yzapp.androidcontainer.core.billing

/**
 * 渠道差异标记（`cn` 渠道）：国内分发无 Play 内购，收费模板不展示。
 */
object DistributionChannel {

    /** 收费（premium）模板是否对用户可见。 */
    const val paidTemplatesVisible: Boolean = false
}
