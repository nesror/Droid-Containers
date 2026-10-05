package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.engine.compose.TemplateCatalog
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板接口的响应构造：**锁定态不下发正文**、语言回退、多语言文案解析。
 * 跑的是内置目录（与 App 同一份数据），所以内容格式也顺带被覆盖。
 */
class TemplateFeedTest {

    private val catalog = TemplateCatalog.builtIn()
    private val unlocked = EntitlementState.Unlocked(EntitlementState.Unlocked.Source.PLAY)
    private val freeId = "mqtt-broker"
    private val premiumId = "nodered"

    private fun template(json: JsonObject, id: String): JsonObject =
        json["templates"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == id }
            .jsonObject

    /** 显式传渠道标记：测试不随构建变体（global/cn）的 [DistributionChannel] 默认值漂移。 */
    private fun build(
        state: EntitlementState,
        language: String,
        paidTemplatesVisible: Boolean = true,
    ): JsonObject = TemplateFeed.build(catalog, state, language, paidTemplatesVisible)

    // ---------------------------------------------------------------- 语言解析

    @Test
    fun `query language wins over accept-language`() {
        assertEquals("zh", TemplateFeed.resolveLanguage("zh", "ru"))
        assertEquals("zh-cn", TemplateFeed.resolveLanguage("zh-CN", null))
    }

    @Test
    fun `accept-language falls back to its first tag`() {
        assertEquals("ru", TemplateFeed.resolveLanguage(null, "ru,en;q=0.8"))
        assertEquals("ja", TemplateFeed.resolveLanguage(null, "ja"))
        assertEquals("zh", TemplateFeed.resolveLanguage(null, "zh;q=0.9,en;q=0.5"))
    }

    @Test
    fun `english is the default and the fallback for unusable tags`() {
        assertEquals("en", TemplateFeed.resolveLanguage(null, null))
        assertEquals("en", TemplateFeed.resolveLanguage("", "  "))
        assertEquals("en", TemplateFeed.resolveLanguage("*", "*"))
        assertEquals("en", TemplateFeed.resolveLanguage("../../etc/passwd", null))
        assertEquals("en", TemplateFeed.resolveLanguage("x".repeat(30), null))
    }

    // ---------------------------------------------------------------- 门禁

    @Test
    fun `locked state hides premium bodies but keeps free ones`() {
        val json = build(EntitlementState.Locked, "en")

        val premium = template(json, premiumId)
        assertTrue(premium["premium"]!!.jsonPrimitive.boolean)
        assertTrue("premium must be flagged as locked", premium["locked"]!!.jsonPrimitive.boolean)
        assertTrue(
            "the compose body must not be sent at all, not merely hidden in the UI",
            premium["compose"] is JsonNull,
        )
        assertNull(premium["compose"]!!.jsonPrimitive.contentOrNull)

        val free = template(json, freeId)
        assertFalse(free["locked"]!!.jsonPrimitive.boolean)
        assertTrue(free["compose"]!!.jsonPrimitive.content.contains("services:"))
    }

    @Test
    fun `an inconclusive entitlement is treated as locked`() {
        val json = build(EntitlementState.Unknown, "en")

        assertFalse(json["entitlement"]!!.jsonObject["unlocked"]!!.jsonPrimitive.boolean)
        assertTrue(template(json, premiumId)["compose"] is JsonNull)
    }

    @Test
    fun `unlocked state serves premium bodies`() {
        val json = build(unlocked, "en")

        assertTrue(json["entitlement"]!!.jsonObject["unlocked"]!!.jsonPrimitive.boolean)
        val premium = template(json, premiumId)
        assertFalse(premium["locked"]!!.jsonPrimitive.boolean)
        assertTrue(premium["compose"]!!.jsonPrimitive.content.contains("services:"))
    }

    @Test
    fun `channel without paid templates omits premium entries entirely`() {
        val json = build(unlocked, "en", paidTemplatesVisible = false)

        val ids = json["templates"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertFalse("premium templates must not be listed at all", ids.contains(premiumId))
        assertTrue("free templates stay listed", ids.contains(freeId))
        assertEquals(
            "exactly the free templates are served",
            catalog.templates.filterNot { it.premium }.map { it.id },
            ids,
        )
        assertTrue(json["entitlement"]!!.jsonObject["unlocked"]!!.jsonPrimitive.boolean)
    }

    // ---------------------------------------------------------------- 内容

    @Test
    fun `template copy is resolved in the requested language`() {
        val zh = template(build(EntitlementState.Locked, "zh"), freeId)
        val en = template(build(EntitlementState.Locked, "en"), freeId)

        assertNotEquals(
            "the same template must read differently in Chinese and English",
            en["name"]!!.jsonPrimitive.content,
            zh["name"]!!.jsonPrimitive.content,
        )
        assertTrue(
            "the Chinese name should come from the template file, not from a fallback",
            zh["name"]!!.jsonPrimitive.content.any { it.code > 0x2E80 },
        )
        assertTrue(zh["desc"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `a regional tag falls back to its language`() {
        val zhHant = template(build(EntitlementState.Locked, "zh-hant-tw"), freeId)
        val zh = template(build(EntitlementState.Locked, "zh"), freeId)

        assertEquals(zh["name"]!!.jsonPrimitive.content, zhHant["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `templates carry services, ports, risk labels and notes`() {
        val json = build(unlocked, "en")
        val smarthome = template(json, "smarthome")

        val services = smarthome["services"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("mqtt", "nodered", "homeassistant"), services.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("eclipse-mosquitto:2", services.first()["image"]!!.jsonPrimitive.content)
        assertEquals(listOf(1883, 1880, 8123), smarthome["containerPorts"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(listOf(1880, 8123), smarthome["webPorts"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertTrue(smarthome["riskLabel"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue("notes are resolved to text", smarthome["notes"]!!.jsonArray.isNotEmpty())
        smarthome["notes"]!!.jsonArray.forEach { note ->
            assertTrue(note.jsonObject["text"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `categories include the fallback group and the language field is echoed`() {
        val json = build(EntitlementState.Locked, "ja")

        assertEquals("ja", json["language"]!!.jsonPrimitive.content)
        val ids = json["categories"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(ids.contains("other"))
        assertEquals("templates are emitted in display order", catalog.templates.map { it.id }, json["templates"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }
}
