package cn.yzapp.androidcontainer.feature.compose

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.billing.BillingFailure
import cn.yzapp.androidcontainer.core.billing.DistributionChannel
import cn.yzapp.androidcontainer.core.billing.EntitlementRepository
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.billing.PlayAvailability
import cn.yzapp.androidcontainer.core.billing.ProductOffer
import cn.yzapp.androidcontainer.core.billing.PurchaseOutcome
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.engine.compose.ComposeTemplate
import cn.yzapp.androidcontainer.core.engine.compose.TemplateCatalog
import cn.yzapp.androidcontainer.core.engine.compose.TemplateGroup
import cn.yzapp.androidcontainer.core.engine.compose.TemplateTaxonomy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 模板库 / 模板详情 / 解锁页共用的 ViewModel（方案 §6.1 ③）：
 * 模板目录 + Pro 权益订阅 + 购买/恢复动作与错误状态。
 *
 * 模板目录取自 `TemplateCatalog.builtIn()`（进程内解析一次，`ContainerApp` 已预热），
 * 因此这里不再自行解析任何模板，也不存在「列表页与详情页各解析一遍」的重复开销；
 * 详情页所需的服务清单与提示直接读 `ComposeTemplate.spec`（目录解析期已算好）。
 *
 * 模板文案不再是 `@StringRes`，而是模板自带的 `LocalizedText`，由 UI 按当前 App 语言解析。
 */
class TemplatesViewModel(app: Application) : AndroidViewModel(app) {

    private val entitlements: EntitlementRepository = DataGraph.entitlementRepository

    /** 内置模板目录。 */
    val catalog: TemplateCatalog = TemplateCatalog.builtIn()

    /** 共享分类学：分类名、风险标签、提示全文。 */
    val taxonomy: TemplateTaxonomy get() = catalog.taxonomy

    /** 渠道差异（`cn` 渠道无内购）：收费模板不展示。 */
    private val paidVisible = DistributionChannel.paidTemplatesVisible

    /** 收费模板是否对用户可见（UI 据此隐藏 Pro 购买入口）。 */
    val paidTemplatesVisible: Boolean get() = paidVisible

    /** 权益状态；`Unknown` 期间 UI 置灰骨架，绝不闪「未解锁」。 */
    val entitlement: StateFlow<EntitlementState> = entitlements.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntitlementState.Unknown)

    /** 商品名称与价格；为 null 表示尚未拿到（价格占位）。 */
    val product: StateFlow<ProductOffer?> = entitlements.product
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Play 服务可用性，用于解锁页降级说明。 */
    val playAvailability: StateFlow<PlayAvailability> = entitlements.playAvailability
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayAvailability.UNKNOWN)

    /** 分类分组（已含分类顺序与分组内顺序），直接来自目录。
     * 收费模板不可见的渠道（`cn`）先剔除 Pro 模板，过滤后为空的分类随之消失，
     * 与 [detailOf] 的过滤同源，保证「列表不可达 + 详情兜底」一致。 */
    val groups: List<TemplateGroup> = catalog.groups.mapNotNull { group ->
        when {
            paidVisible -> group
            else -> group.templates
                .filterNot { it.premium }
                .takeIf { it.isNotEmpty() }
                ?.let { group.copy(templates = it) }
        }
    }

    private val _busy = MutableStateFlow(false)

    /** 购买 / 恢复进行中。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _failure = MutableStateFlow<BillingFailure?>(null)

    /** 最近一次购买/恢复的失败原因（成功或关闭后清空）。 */
    val failure: StateFlow<BillingFailure?> = _failure.asStateFlow()

    private val _purchased = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 购买成功一次性事件（UI 弹感谢提示）。 */
    val purchased: SharedFlow<Unit> = _purchased.asSharedFlow()

    init {
        // 进入模板库触发一次静默恢复查询（内部按 10 分钟节流）
        viewModelScope.launch { entitlements.refresh(force = false) }
    }

    /** 模板详情数据；正文解析结果已在目录解析期算好（[ComposeTemplate.spec]），无需二次解析。
     * 收费模板不可见的渠道返回 `null`（列表不可达 + 详情兜底，双保险）。 */
    fun detailOf(templateId: String): ComposeTemplate? =
        catalog.byId(templateId)?.takeIf { paidVisible || !it.premium }

    /** 未解锁（含 `Unknown`）的 Pro 模板不得进入使用流程，但仍可浏览详情。 */
    fun canUse(template: ComposeTemplate, state: EntitlementState): Boolean =
        !template.premium || state is EntitlementState.Unlocked

    fun purchase(activity: Activity) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _failure.value = null
            try {
                when (val outcome = entitlements.purchase(activity)) {
                    PurchaseOutcome.Success -> _purchased.tryEmit(Unit)
                    is PurchaseOutcome.Failure -> _failure.value = outcome.failure
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun restore() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _failure.value = null
            try {
                when (val outcome = entitlements.restore()) {
                    PurchaseOutcome.Success -> _purchased.tryEmit(Unit)
                    is PurchaseOutcome.Failure -> _failure.value = outcome.failure
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissFailure() {
        _failure.value = null
    }

    /**
     * 把模板交回编排页：[ComposeDraftBus] 存草稿，编排页消费后预填编辑器。
     * 默认项目名用模板 id（ASCII），规避「用户数据被本地化」的坑（方案 §4.3）。
     */
    fun handOffDraft(template: ComposeTemplate, projectName: String) {
        ComposeDraftBus.offer(
            PendingDraft(
                name = projectName.trim().ifBlank { template.suggestedProjectName },
                yaml = template.yaml,
            ),
        )
    }
}
