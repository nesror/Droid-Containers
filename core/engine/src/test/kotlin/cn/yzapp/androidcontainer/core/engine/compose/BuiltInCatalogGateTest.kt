package cn.yzapp.androidcontainer.core.engine.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私有层守门测试（开源方案 §5）：绑定真实内置模板的产品边界断言。
 *
 * 公开仓的 `src/main/templates/` 目录为空（真实模板与 `_taxonomy.yaml` 留在私有层），
 * 这些断言在公开仓没有意义，因此独立成类、只存在于私有仓——文件名与公开仓收缩版的
 * `TemplateCatalogTest` 不同，merge 时互不覆盖、互不冲突。
 *
 * 覆盖内容：22 个模板 id 精确有序、ollama 缺席、免费边界 = 4 及精确集合、
 * 分组数与覆盖完整性、fallback 分类四语言且居末、内置文案四语言齐全、smarthome 真机形状。
 */
class BuiltInCatalogGateTest {

    private val catalog = TemplateCatalog.builtIn()
    private val templates = catalog.templates
    private val requiredLanguages = listOf("en", "zh", "ru", "ja")

    @Test
    fun `the built-in catalog contains the agreed templates in display order`() {
        assertEquals(
            listOf(
                "mqtt-broker", "nodered", "smarthome", "homeassistant", "n8n", "qinglong",
                "filebrowser", "syncthing", "alist",
                "web-redis", "code-server", "python-lab", "mariadb-adminer",
                "memos", "sun-panel", "navidrome", "vikunja", "halo",
                "uptime-kuma", "vaultwarden", "ddns-go", "lucky",
            ),
            templates.map { it.id },
        )
    }

    @Test
    fun `ollama is not offered because local llms are not viable on android`() {
        assertNull("local LLM inference is not practical on Android devices", catalog.byId("ollama"))
    }

    @Test
    fun `free tier is exactly the agreed templates`() {
        assertEquals("free template count is part of the product boundary", 4, catalog.free.size)
        assertEquals(
            "free template ids are relied upon by store copy and in-app hints",
            setOf("web-redis", "python-lab", "mqtt-broker", "homeassistant"),
            catalog.free.map { it.id }.toSet(),
        )
        assertEquals(templates.size, catalog.free.size + catalog.premium.size)
    }

    @Test
    fun `category grouping keeps every template reachable exactly once`() {
        val grouped = catalog.groups.flatMap { it.templates }
        assertEquals("groups must cover every template exactly once, in order", templates, grouped)
        assertEquals(5, catalog.groups.size)
        catalog.groups.forEach { group ->
            assertTrue("empty group must not be emitted: ${group.category.id}", group.templates.isNotEmpty())
            assertEquals(
                "templates inside a group must be ordered by their `order` field",
                group.templates.sortedWith(compareBy({ it.order }, { it.id })),
                group.templates,
            )
        }
    }

    @Test
    fun `the built-in taxonomy declares the fallback category last with all four languages`() {
        val fallback = catalog.taxonomy.category(TemplateCatalog.FALLBACK_CATEGORY_ID)
        assertNotNull("built-ins declare the fallback category so it ships with proper translations", fallback)
        requiredLanguages.forEach { language ->
            assertTrue(
                "the fallback category is missing $language",
                fallback!!.name.has(language),
            )
        }
        assertEquals(
            "the fallback group must be listed last",
            TemplateCatalog.FALLBACK_CATEGORY_ID,
            catalog.taxonomy.categories.last().id,
        )
    }

    @Test
    fun `every template provides all four languages for name and desc`() {
        templates.forEach { template ->
            requiredLanguages.forEach { language ->
                assertTrue(
                    "template ${template.id} is missing the $language name (four languages are mandatory for built-ins)",
                    template.name.has(language),
                )
                assertTrue(
                    "template ${template.id} is missing the $language desc",
                    template.desc.has(language),
                )
            }
        }
    }

    @Test
    fun `every template has all three risk labels and all shared note texts in four languages`() {
        requiredLanguages.forEach { language ->
            TemplateRisk.entries.forEach { risk ->
                assertTrue(
                    "risk label ${risk.id} is missing $language",
                    catalog.taxonomy.riskLabel(risk).has(language),
                )
            }
            catalog.taxonomy.notes.forEach { (id, note) ->
                assertTrue("note $id is missing $language", note.text.has(language))
            }
            catalog.taxonomy.categories.forEach { category ->
                assertTrue("category ${category.id} is missing $language", category.name.has(language))
            }
        }
    }

    @Test
    fun `smarthome keeps the verified real-device shape`() {
        val template = catalog.byId("smarthome")
        assertNotNull(template)
        assertEquals(
            "smarthome services and their startup order are a verified real-device result",
            listOf("mqtt", "nodered", "homeassistant"),
            template!!.spec.startupSequence(),
        )
        assertEquals(TemplateRisk.VERIFIED, template.risk)
        assertTrue("first_start_slow" in template.noteIds)
    }
}
