package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.billing.DistributionChannel
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.engine.compose.ComposeTemplate
import cn.yzapp.androidcontainer.core.engine.compose.LocalizedText
import cn.yzapp.androidcontainer.core.engine.compose.TemplateCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET /api/v1/templates` 的响应构造。
 *
 * 抽成纯函数（不碰 Ktor）是为了让**权益门禁与语言回退可被单测覆盖**——这是本次最容易出错、
 * 也最不能在真机上试错的部分。
 *
 * 门禁要点：锁定的 Pro 模板**不下发 `compose` 正文**（`compose: null` 且 `locked: true`），
 * 前端那把锁只是表现层；免费模板恒有正文。
 */
object TemplateFeed {

    /** 与 App 的四语言、`_taxonomy.yaml` 对齐。 */
    val LANGUAGES: List<String> = listOf("en", "zh", "ru", "ja")

    /**
     * 语言解析：`?lang=` 优先 → `Accept-Language` 的第一个标签 → **默认英语**。
     *
     * 这里只负责「取出一个语言标签」；真正的回退链（`zh-Hant → zh → en → 任一`）
     * 由 [LocalizedText.resolve] 在解析文案时完成。
     */
    fun resolveLanguage(queryLang: String?, acceptLanguage: String?): String {
        sanitize(queryLang)?.let { return it }
        acceptLanguage?.split(',')?.firstOrNull()?.let { sanitize(it)?.let { tag -> return tag } }
        return LocalizedText.DEFAULT_LANGUAGE
    }

    /** 只接受形如 `zh` / `zh-hant` 的标签；`*`、空值与异常字符一律忽略。 */
    private fun sanitize(raw: String?): String? {
        val tag = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (tag.isEmpty() || tag == "*" || tag.length > 20) return null
        if (!tag.all { it.isLetterOrDigit() || it == '-' }) return null
        return tag
    }

    fun build(catalog: TemplateCatalog, state: EntitlementState, language: String): JsonObject =
        build(catalog, state, language, DistributionChannel.paidTemplatesVisible)

    /**
     * 可测版本：[paidTemplatesVisible] 显式传入（`cn` 渠道为 `false`，收费模板整体不下发）。
     */
    fun build(
        catalog: TemplateCatalog,
        state: EntitlementState,
        language: String,
        paidTemplatesVisible: Boolean,
    ): JsonObject {
        val unlocked = EntitlementGate.isAllowed(state)
        val visibleTemplates = if (paidTemplatesVisible) {
            catalog.templates
        } else {
            catalog.templates.filterNot { it.premium }
        }
        return buildJsonObject {
            put("language", language)
            put(
                "entitlement",
                buildJsonObject {
                    put("state", EntitlementGate.stateName(state))
                    put("unlocked", unlocked)
                },
            )
            put("categories", buildJsonArray {
                catalog.taxonomy.categories.forEach { category ->
                    add(buildJsonObject {
                        put("id", category.id)
                        put("name", category.name.resolve(language))
                    })
                }
            })
            put("templates", buildJsonArray {
                // 目录已按「分类顺序 + 分类内 order」排好，前端不再排序
                visibleTemplates.forEach { add(templateJson(it, catalog, language, unlocked)) }
            })
        }
    }

    private fun templateJson(
        template: ComposeTemplate,
        catalog: TemplateCatalog,
        language: String,
        unlocked: Boolean,
    ): JsonObject {
        val locked = template.premium && !unlocked
        return buildJsonObject {
            put("id", template.id)
            put("category", template.categoryId)
            put("name", template.name.resolve(language))
            put("desc", template.desc.resolve(language))
            put("risk", template.risk.id)
            put("riskLabel", catalog.taxonomy.riskLabel(template.risk).resolve(language))
            put("premium", template.premium)
            put("locked", locked)
            put("services", buildJsonArray {
                template.services.forEach { service ->
                    add(buildJsonObject {
                        put("name", service.name)
                        put("image", service.image)
                        put("ports", strings(service.ports))
                    })
                }
            })
            put("containerPorts", numbers(template.containerPorts))
            put("webPorts", numbers(template.webPorts))
            put("notes", buildJsonArray {
                template.noteIds.forEach { noteId ->
                    val note = catalog.taxonomy.note(noteId) ?: return@forEach
                    add(buildJsonObject {
                        put("id", note.id)
                        put("text", note.text.resolve(language))
                    })
                }
            })
            // 锁定态不下发正文：这是内容级门禁的实质，不是 UI 层的隐藏
            put("compose", if (locked) JsonNull else JsonPrimitive(template.yaml))
        }
    }

    private fun numbers(values: List<Int>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun strings(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
