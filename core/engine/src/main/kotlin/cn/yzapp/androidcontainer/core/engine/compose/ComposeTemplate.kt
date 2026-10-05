package cn.yzapp.androidcontainer.core.engine.compose

/**
 * 风险分级（等级本身是 App 固定的三种，展示文案在 `_taxonomy.yaml` 的 `risks` 里）。
 *
 * 决定详情页标签，也决定是否可在未真机验证时发布。
 */
enum class TemplateRisk(val id: String) {

    /** 已在真机验证。 */
    VERIFIED("verified"),

    /** 预期可用（发布前须真机逐条验证）。 */
    LIKELY("likely"),

    /** 实验性（绕过 entrypoint / 需目录初始化 / 体积大）。 */
    EXPERIMENTAL("experimental");

    companion object {

        /** 模板文件里 `risk:` 的取值；未知值返回 null（调用方按最保守的等级处理并记 issue）。 */
        fun fromId(raw: String?): TemplateRisk? = when (raw?.trim()?.lowercase()) {
            VERIFIED.id -> VERIFIED
            LIKELY.id -> LIKELY
            EXPERIMENTAL.id -> EXPERIMENTAL
            else -> null
        }
    }
}

/**
 * 被**代码逻辑**引用的共享提示 id。
 *
 * 提示内容本身是数据（文案在 `_taxonomy.yaml`），但个别提示承载了校验语义，
 * 例如「绕过 /init」必须显式告知，所以这些 id 以常量形式固化，避免引擎里出现裸字符串。
 */
object TemplateNotes {

    /** 绕过镜像 `/init` 入口，改为直接启动服务（与 `ComposeIssueKind.PID1_RISK` 配对）。 */
    const val PID1_WORKAROUND = "pid1_workaround"

    /** 端口不映射、直接给可访问地址；文案含 `{urls}` 占位符，必须与 `webPorts` 成对出现。 */
    const val PORT_DIRECT = "port_direct"
}

/** 分类定义（来自 `_taxonomy.yaml`）。[order] 决定分组展示顺序。 */
data class TemplateCategoryInfo(
    val id: String,
    val order: Int,
    val name: LocalizedText,
)

/** 共享提示定义（来自 `_taxonomy.yaml`）。文案里可含 `{urls}` 占位符。 */
data class TemplateNoteInfo(
    val id: String,
    val text: LocalizedText,
)

/** UI 直接消费的分组；[templates] 已按模板 `order` 排好。 */
data class TemplateGroup(
    val category: TemplateCategoryInfo,
    val templates: List<ComposeTemplate>,
)

/**
 * 一个编排模板（由 `core/engine/src/main/templates/<id>.yaml` 解析而来）。
 *
 * 与 [ComposeSamples] 同构：**纯数据，无任何 Android / 资源引用**；文案由 [LocalizedText] 自带。
 * 正文的解析结果 [spec] 在目录解析期一次算好，因此 [containerPorts] / [issues] 不会重复解析 YAML。
 */
data class ComposeTemplate(
    /** 稳定标识，同时用作默认项目名（ASCII、已满足 [ComposeNaming.sanitize]）。 */
    val id: String,
    /** 分类 id，取值必须存在于 `_taxonomy.yaml`。 */
    val categoryId: String,
    /** 分类内展示顺序，小的在前。 */
    val order: Int,
    val risk: TemplateRisk,
    /** `true` = 需 Pro 解锁（模板包一次性商品）。 */
    val premium: Boolean,
    val name: LocalizedText,
    val desc: LocalizedText,
    /** 共享提示 id，顺序即展示顺序。 */
    val noteIds: List<String>,
    /**
     * 对外提供 **HTTP 服务** 的端口，供「用系统浏览器直接打开」使用。
     * 必须 ⊆ 正文声明的端口（解析期校验）；MQTT / MySQL / P2P 等非 HTTP 端口不列入。
     */
    val webPorts: List<Int>,
    /** compose 正文（原样），供「使用模板」写入项目与「YAML 预览」展示。 */
    val yaml: String,
    /** 正文解析结果（解析期一次算好）。 */
    val spec: ComposeSpec,
    /** ASCII，默认即 [id]。 */
    val suggestedProjectName: String = id,
) {

    val services: List<ComposeService> get() = spec.services

    /** 正文里标记的非致命提示（未实现键、PID 1 风险等）。 */
    val issues: List<ComposeIssue> get() = spec.issues

    /** 正文声明的容器内监听端口（去重、保持声明顺序），即该模板的端口记录。 */
    val containerPorts: List<Int> get() = spec.containerPorts

    /** 按语言标签取模板名；缺失时返回空串，由 UI 用兜底文案展示。 */
    fun name(languageTag: String?): String = name.resolve(languageTag)

    fun desc(languageTag: String?): String = desc.resolve(languageTag)
}
