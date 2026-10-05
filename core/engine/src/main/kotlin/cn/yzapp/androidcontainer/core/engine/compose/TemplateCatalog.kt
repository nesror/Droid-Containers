package cn.yzapp.androidcontainer.core.engine.compose

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/** 模板描述文件的解析提示。**一律非致命**：坏模板被跳过并记录，绝不让模板库整页失败。 */
enum class TemplateIssueKind {

    /** YAML 语法/结构错误。 */
    PARSE_FAILED,

    /** 必填字段缺失（`id` / `category` / `compose`）。 */
    MISSING_FIELD,

    /** 字段值不合法（`order` 非数字、`name` 不是语言映射等）。 */
    INVALID_VALUE,

    /** 分类 id 不在 `_taxonomy.yaml` 里。 */
    UNKNOWN_CATEGORY,

    /** 整份文件就是 compose 正文、没有模板元数据（按「文件名 = id、归入兜底分类」容错处理）。 */
    MISSING_METADATA,

    /** 风险等级不是 verified / likely / experimental，按 experimental 处理。 */
    UNKNOWN_RISK,

    /** 提示 id 不在 `_taxonomy.yaml` 里，该提示被忽略。 */
    UNKNOWN_NOTE,

    /** 描述文件里有未知键（前向兼容：忽略但记录，便于发现拼写错误）。 */
    UNKNOWN_KEY,

    /** 多语言文案不完整（至少要能回退到 `en`）。 */
    TEXT_INCOMPLETE,

    /** `webPorts` 里的端口不在正文端口内，该端口被忽略（避免给出打不开的地址）。 */
    PORT_NOT_IN_BODY,

    /** `id` 与文件名不一致。 */
    ID_MISMATCH,

    /** id 重复，后出现的被忽略。 */
    DUPLICATE_ID,

    /** 正文命中 PID 1 风险，但没有引用 `pid1_workaround` 提示（强制「风险必须显式告知」）。 */
    PID1_NOT_DISCLOSED,

    /** `port_direct` 提示与 `webPorts` 必须成对：缺一就会渲染出没有地址可点的提示，或漏掉地址提示。 */
    PORT_NOTE_MISMATCH,
}

data class TemplateIssue(
    /** 出问题的模板 / 文件 id。 */
    val templateId: String?,
    val kind: TemplateIssueKind,
    val message: String,
)

/**
 * 共享分类学（`_taxonomy.yaml`）：分类的展示顺序与名称、风险标签、共享提示全文。
 *
 * 单独成文件是为了让「新增一个模板」只改一个文件：分类与共享提示跨模板复用，不该在每个模板里重复翻译。
 */
class TemplateTaxonomy(
    /** 已按 `order` 排序。 */
    val categories: List<TemplateCategoryInfo>,
    /** 风险 id → 标签文案。 */
    val riskLabels: Map<String, LocalizedText>,
    /** 提示 id → 定义。 */
    val notes: Map<String, TemplateNoteInfo>,
) {

    val isEmpty: Boolean get() = categories.isEmpty()

    fun category(id: String): TemplateCategoryInfo? = categories.firstOrNull { it.id == id }

    fun note(id: String): TemplateNoteInfo? = notes[id]

    fun riskLabel(risk: TemplateRisk): LocalizedText = riskLabels[risk.id] ?: LocalizedText.EMPTY

    companion object {

        val EMPTY: TemplateTaxonomy = TemplateTaxonomy(emptyList(), emptyMap(), emptyMap())

        /** 分类未声明 `order` 时的兜底值（排在最后）。 */
        private const val DEFAULT_CATEGORY_ORDER = 1000

        fun parse(text: String, issues: MutableList<TemplateIssue>): TemplateTaxonomy {
            // 空分类学 = 私有层未随源码公开（开源公开仓的内置目录为空）：
            // 直接按 EMPTY 处理，不记 issue，让「零 issue」守门在公开仓同样成立
            if (text.isBlank()) return EMPTY
            val root = rootMapOf(text, TAXONOMY_ID, issues) ?: return EMPTY

            val categories = (root.node("categories") as? YamlList)?.items.orEmpty().mapNotNull { item ->
                val map = item as? YamlMap ?: return@mapNotNull null
                val id = map.scalar("id")?.content?.trim()?.lowercase().orEmpty()
                if (id.isEmpty()) {
                    issues += TemplateIssue(
                        TAXONOMY_ID,
                        TemplateIssueKind.MISSING_FIELD,
                        "category entry without id was ignored",
                    )
                    return@mapNotNull null
                }
                val order = map.scalar("order")?.content?.trim()?.toIntOrNull() ?: DEFAULT_CATEGORY_ORDER
                TemplateCategoryInfo(id = id, order = order, name = localized(map.node("name")))
            }.sortedWith(compareBy({ it.order }, { it.id }))

            val riskLabels = LinkedHashMap<String, LocalizedText>()
            (root.node("risks") as? YamlMap)?.pairs().orEmpty().forEach { (key, value) ->
                riskLabels[key.content.trim().lowercase()] = localized(value)
            }

            val notes = LinkedHashMap<String, TemplateNoteInfo>()
            (root.node("notes") as? YamlMap)?.pairs().orEmpty().forEach { (key, value) ->
                val id = key.content.trim().lowercase()
                notes[id] = TemplateNoteInfo(id = id, text = localized(value))
            }

            if (categories.isEmpty()) {
                issues += TemplateIssue(
                    TAXONOMY_ID,
                    TemplateIssueKind.MISSING_FIELD,
                    "no categories defined: every template will be skipped",
                )
            }
            return TemplateTaxonomy(categories, riskLabels, notes)
        }
    }
}

/**
 * 内置模板目录（内容源：`core/engine/src/main/templates/`，一个模板一个 YAML 文件）。
 *
 * 与远端模板共用同一套解析器：远端内容将来只需多喂几份文本进来合并，
 * 因此这里刻意不做「只在构建期成立」的假设。解析结果缓存在 [builtIn]。
 */
class TemplateCatalog private constructor(
    val taxonomy: TemplateTaxonomy,
    val templates: List<ComposeTemplate>,
    val issues: List<TemplateIssue>,
) {

    /** 按分类顺序分组，空分类不出现；模板按自身 `order` 排序。 */
    val groups: List<TemplateGroup> = taxonomy.categories.mapNotNull { category ->
        val list = templates.filter { it.categoryId == category.id }
        if (list.isEmpty()) null else TemplateGroup(category, list)
    }

    val free: List<ComposeTemplate> get() = templates.filter { !it.premium }

    val premium: List<ComposeTemplate> get() = templates.filter { it.premium }

    fun byId(id: String): ComposeTemplate? = templates.firstOrNull { it.id == id }

    companion object {

        /** 未声明 `order` 的模板兜底值（排在分类末尾）。 */
        private const val DEFAULT_TEMPLATE_ORDER = 1000

        /**
         * 兜底分类：模板没写 `category`（或写了但分类学里没有它）时归到这里，而不是丢掉整个模板。
         * `_taxonomy.yaml` 可以显式声明同名分类来接管它的排序与文案；没声明时用本值排在最后。
         */
        const val FALLBACK_CATEGORY_ID = "other"

        private const val FALLBACK_CATEGORY_ORDER = 10_000

        private val KNOWN_KEYS = setOf(
            "id", "category", "order", "risk", "premium", "name", "desc", "notes", "webPorts", "compose",
        )

        private val builtInLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            parse(TemplateYaml.TAXONOMY, TemplateYaml.TEMPLATES)
        }

        /** 内置目录（首次访问解析一次，之后复用）。 */
        fun builtIn(): TemplateCatalog = builtInLazy.value

        /** 供 Application 在后台线程预热，避免首次进入模板库时在主线程解析。 */
        fun warmUp() {
            builtIn()
        }

        /**
         * 解析分类学 + 若干模板文档。**不抛异常**：坏内容降级为 [issues]。
         *
         * 容错规则（目标是「丢一个 compose 文件进来也能用」）：
         * - 整份文件就是 compose 正文（根上有 `services`、没有 `compose`）→ 取文件名为 id、归入兜底分类；
         * - `id` 缺失 → 用文件名；
         * - `category` 缺失或不在分类学里 → 归入 [FALLBACK_CATEGORY_ID]，**不丢弃模板**；
         * - `name` 缺失 → 用 id 兜底；`desc` 允许整体缺失；
         * - `risk` 缺失 → 按最保守的 `experimental`；`order` 缺失 → 排在分类末尾；
         * - `premium` 缺失 → 保持锁定（宁可少给，不能因为漏写就把付费内容放出去）；
         * - 无法解析正文、或完全没有 `services` 的文件 → 仍然丢弃（这类内容没有可展示的语义）。
         *
         * @param documents 文件名（不含扩展名，即模板 id 的期望值）→ 文件原文
         */
        fun parse(taxonomyText: String, documents: Map<String, String>): TemplateCatalog {
            val issues = mutableListOf<TemplateIssue>()
            val taxonomy = TemplateTaxonomy.parse(taxonomyText, issues)

            val templates = mutableListOf<ComposeTemplate>()
            val seen = mutableSetOf<String>()
            documents.forEach { (sourceName, text) ->
                val template = parseDocument(sourceName, text, taxonomy, issues) ?: return@forEach
                if (!seen.add(template.id)) {
                    issues += TemplateIssue(
                        template.id,
                        TemplateIssueKind.DUPLICATE_ID,
                        "template id \"${template.id}\" is already defined; the later definition was ignored",
                    )
                    return@forEach
                }
                templates += template
            }

            // 兜底分类：有模板落到它、而分类学里又没声明时补一个。
            // 名称留空 —— 引擎不含任何文案，UI 用本地化的「其他」文案兜底。
            val effectiveTaxonomy = if (
                templates.any { it.categoryId == FALLBACK_CATEGORY_ID } &&
                taxonomy.category(FALLBACK_CATEGORY_ID) == null
            ) {
                TemplateTaxonomy(
                    categories = taxonomy.categories + TemplateCategoryInfo(
                        id = FALLBACK_CATEGORY_ID,
                        order = FALLBACK_CATEGORY_ORDER,
                        name = LocalizedText.EMPTY,
                    ),
                    riskLabels = taxonomy.riskLabels,
                    notes = taxonomy.notes,
                )
            } else {
                taxonomy
            }

            val categoryOrder = effectiveTaxonomy.categories.withIndex().associate { (index, category) -> category.id to index }
            val sorted = templates.sortedWith(
                compareBy(
                    { categoryOrder[it.categoryId] ?: Int.MAX_VALUE },
                    { it.order },
                    { it.id },
                ),
            )
            return TemplateCatalog(effectiveTaxonomy, sorted, issues)
        }

        private fun parseDocument(
            sourceName: String,
            text: String,
            taxonomy: TemplateTaxonomy,
            issues: MutableList<TemplateIssue>,
        ): ComposeTemplate? {
            val root = rootMapOf(text, sourceName, issues) ?: return null

            // 容错一：整份文件就是 compose 正文（用户直接丢一个 compose 文件进来）。
            // 判定依据是「根上有 services、没有 compose」—— compose 文档本来就是这个形状。
            val headless = root.node("compose") == null && root.node("services") != null
            if (headless) {
                issues += TemplateIssue(
                    sourceName,
                    TemplateIssueKind.MISSING_METADATA,
                    "no template metadata: the file name is used as id and the template is filed under " +
                        "\"$FALLBACK_CATEGORY_ID\"",
                )
            } else {
                root.keys().forEach { key ->
                    if (key.content !in KNOWN_KEYS) {
                        issues += TemplateIssue(
                            sourceName,
                            TemplateIssueKind.UNKNOWN_KEY,
                            "unknown key \"${key.content}\" was ignored",
                        )
                    }
                }
            }

            // 容错二：id 缺失时用文件名（文件名本来就该是 id，不用因此丢掉模板）
            val declaredId = root.scalar("id")?.content?.trim().orEmpty()
            val id = declaredId.ifEmpty { sourceName }
            if (declaredId.isEmpty()) {
                issues += TemplateIssue(id, TemplateIssueKind.MISSING_FIELD, "missing key \"id\"; the file name was used")
            } else if (declaredId != sourceName) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.ID_MISMATCH,
                    "id \"$declaredId\" does not match its file name \"$sourceName\"",
                )
            }

            // 容错三：category 缺失或不在分类学里 → 归入兜底分类，而不是丢掉整个模板
            val declaredCategory = root.scalar("category")?.content?.trim()?.lowercase().orEmpty()
            val categoryId = when {
                declaredCategory.isEmpty() -> {
                    issues += TemplateIssue(
                        id,
                        TemplateIssueKind.MISSING_FIELD,
                        "missing key \"category\"; filed under \"$FALLBACK_CATEGORY_ID\"",
                    )
                    FALLBACK_CATEGORY_ID
                }

                taxonomy.category(declaredCategory) == null -> {
                    issues += TemplateIssue(
                        id,
                        TemplateIssueKind.UNKNOWN_CATEGORY,
                        "category \"$declaredCategory\" is not defined in $TAXONOMY_FILE; " +
                            "filed under \"$FALLBACK_CATEGORY_ID\"",
                    )
                    FALLBACK_CATEGORY_ID
                }

                else -> declaredCategory
            }

            val risk = TemplateRisk.fromId(root.scalar("risk")?.content)
            if (risk == null) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.UNKNOWN_RISK,
                    "risk is missing or unknown; treated as \"${TemplateRisk.EXPERIMENTAL.id}\"",
                )
            }

            val premium = root.scalar("premium")?.content?.trim()?.toBooleanStrictOrNull()
            if (premium == null) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.MISSING_FIELD,
                    "premium is missing or not a boolean; kept locked to avoid giving away paid content",
                )
            }

            val order = root.scalar("order")?.content?.trim()?.toIntOrNull()
            if (order == null && root.node("order") != null) {
                issues += TemplateIssue(id, TemplateIssueKind.INVALID_VALUE, "order is not an integer; used the default")
            }

            // 容错四：name 缺失时用 id 兜底（至少有个能读的标题）；desc 允许整体缺失（UI 会隐藏该行）
            val declaredName = localized(root.node("name"))
            val name = if (declaredName.isEmpty) {
                LocalizedText.of(mapOf(LocalizedText.DEFAULT_LANGUAGE to id))
            } else {
                declaredName
            }
            val desc = localized(root.node("desc"))
            if (!name.has(LocalizedText.DEFAULT_LANGUAGE)) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.TEXT_INCOMPLETE,
                    "name must provide \"${LocalizedText.DEFAULT_LANGUAGE}\" so other languages can fall back to it",
                )
            }
            if (!desc.isEmpty && !desc.has(LocalizedText.DEFAULT_LANGUAGE)) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.TEXT_INCOMPLETE,
                    "desc must provide \"${LocalizedText.DEFAULT_LANGUAGE}\" so other languages can fall back to it",
                )
            }

            val noteIds = (root.node("notes") as? YamlList)?.items.orEmpty().mapNotNull { item ->
                val noteId = (item as? YamlScalar)?.content?.trim()?.lowercase()
                if (noteId.isNullOrEmpty()) return@mapNotNull null
                if (taxonomy.note(noteId) == null) {
                    issues += TemplateIssue(
                        id,
                        TemplateIssueKind.UNKNOWN_NOTE,
                        "note \"$noteId\" is not defined in $TAXONOMY_FILE and was ignored",
                    )
                    return@mapNotNull null
                }
                noteId
            }.distinct()

            // 无元数据时整份文件就是正文；有元数据时取 compose 字段（块标量会带一个尾部换行）
            val compose = (root.scalar("compose")?.content ?: if (headless) text else null)?.trimEnd('\n')
            if (compose.isNullOrBlank()) {
                issues += TemplateIssue(id, TemplateIssueKind.MISSING_FIELD, "missing required key \"compose\"")
                return null
            }

            val spec = try {
                ComposeParser.parse(compose)
            } catch (e: ComposeParseException) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.PARSE_FAILED,
                    "compose body is invalid: ${e.displayMessage}; the template was skipped",
                )
                return null
            }

            val declaredPorts = spec.services
                .flatMap { service -> service.ports }
                .mapNotNull { raw -> raw.substringBefore('/').substringAfterLast(':').trim().toIntOrNull() }
                .toSet()
            val requestedPorts = (root.node("webPorts") as? YamlList)?.items.orEmpty().mapNotNull { item ->
                (item as? YamlScalar)?.content?.trim()?.toIntOrNull()
            }
            val webPorts = requestedPorts.filter { port ->
                val ok = port in declaredPorts
                if (!ok) {
                    issues += TemplateIssue(
                        id,
                        TemplateIssueKind.PORT_NOT_IN_BODY,
                        "webPorts contains $port which is not declared in the compose body; it was ignored",
                    )
                }
                ok
            }

            // 「风险必须显式告知」只在有元数据的模板上检查：无元数据的文件根本无法声明提示，
            // 而且 PID1_RISK 本身仍会作为 ComposeIssue 出现在详情页，风险不会因此被藏起来。
            if (!headless && spec.issues.any { it.kind == ComposeIssueKind.PID1_RISK } &&
                TemplateNotes.PID1_WORKAROUND !in noteIds
            ) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.PID1_NOT_DISCLOSED,
                    "the compose body hits a PID 1 risk but note \"${TemplateNotes.PID1_WORKAROUND}\" is not referenced",
                )
            }

            if ((webPorts.isNotEmpty()) != (TemplateNotes.PORT_DIRECT in noteIds)) {
                issues += TemplateIssue(
                    id,
                    TemplateIssueKind.PORT_NOTE_MISMATCH,
                    "note \"${TemplateNotes.PORT_DIRECT}\" and webPorts must be declared together",
                )
            }

            return ComposeTemplate(
                id = id,
                categoryId = categoryId,
                order = order ?: DEFAULT_TEMPLATE_ORDER,
                risk = risk ?: TemplateRisk.EXPERIMENTAL,
                premium = premium ?: true,
                name = name,
                desc = desc,
                noteIds = noteIds,
                webPorts = webPorts,
                yaml = compose,
                spec = spec,
            )
        }
    }
}

/** `_taxonomy.yaml` 的文件名（不含扩展名），也是它出现在 issue 里的标识。 */
internal const val TAXONOMY_ID = "_taxonomy"

internal const val TAXONOMY_FILE = "$TAXONOMY_ID.yaml"

/** 解析文档根映射；失败时记 issue 并返回 null（调用方据此跳过该文档）。 */
private fun rootMapOf(text: String, source: String, issues: MutableList<TemplateIssue>): YamlMap? = try {
    val root = Yaml.default.parseToYamlNode(text)
    root as? YamlMap ?: run {
        issues += TemplateIssue(source, TemplateIssueKind.PARSE_FAILED, "root must be a key-value mapping")
        null
    }
} catch (e: YamlException) {
    issues += TemplateIssue(source, TemplateIssueKind.PARSE_FAILED, "YAML error: ${e.message} (line ${e.line})")
    null
} catch (e: Exception) {
    issues += TemplateIssue(source, TemplateIssueKind.PARSE_FAILED, e.message ?: "YAML parse failed")
    null
}

/** `{en: ..., zh: ...}` → [LocalizedText]；非映射结构返回空（由调用方按缺失处理）。 */
private fun localized(node: YamlNode?): LocalizedText {
    val map = node as? YamlMap ?: return LocalizedText.EMPTY
    val values = LinkedHashMap<String, String>()
    map.pairs().forEach { (key, value) ->
        val text = (value as? YamlScalar)?.content ?: return@forEach
        values[key.content.trim()] = text
    }
    return LocalizedText.of(values)
}
