package cn.yzapp.androidcontainer.core.engine.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LocalizedText] 的回退链是模板多语言的地基，单独覆盖。 */
class LocalizedTextTest {

    private val text = LocalizedText.of(
        mapOf(
            "en" to "English",
            "zh" to "中文",
            "zh-hant" to "繁體中文",
        ),
    )

    @Test
    fun `resolves an exact regional tag first`() {
        assertEquals("繁體中文", text.resolve("zh-Hant"))
        assertEquals("繁體中文", text.resolve("zh-Hant-TW"))
    }

    @Test
    fun `falls back from a regional tag to its language`() {
        assertEquals("中文", text.resolve("zh-CN"))
        assertEquals("中文", text.resolve("zh"))
    }

    @Test
    fun `falls back to english for an unknown language`() {
        assertEquals("English", text.resolve("ru"))
        assertEquals("English", text.resolve(""))
        assertEquals("English", text.resolve(null))
    }

    @Test
    fun `falls back to any available language when english is missing`() {
        val noEnglish = LocalizedText.of(mapOf("ja" to "日本語"))
        assertEquals("日本語", noEnglish.resolve("ru"))
    }

    @Test
    fun `normalizes keys and drops blank values`() {
        val mixed = LocalizedText.of(mapOf(" ZH-Hant " to "繁體", "ru" to "   ", "en" to "English"))
        assertTrue(mixed.has("zh-hant"))
        assertFalse("blank values must be dropped rather than resolved to whitespace", mixed.has("ru"))
        assertEquals("繁體", mixed.resolve("zh-Hant"))
        assertEquals(listOf("zh-hant", "en"), mixed.languages)
    }

    @Test
    fun `empty text resolves to empty string instead of throwing`() {
        assertEquals("", LocalizedText.EMPTY.resolve("en"))
        assertEquals("", LocalizedText.of(emptyMap()).resolve("zh"))
        assertTrue(LocalizedText.EMPTY.isEmpty)
    }

    @Test
    fun `textOrNull does not fall back`() {
        assertEquals(null, text.textOrNull("ru"))
        assertEquals("English", text.textOrNull("en"))
    }
}
