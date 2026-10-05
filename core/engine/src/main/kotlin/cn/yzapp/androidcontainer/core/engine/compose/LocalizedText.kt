package cn.yzapp.androidcontainer.core.engine.compose

/**
 * 模板内容自带的多语言文案（**不经过 Android string 资源**）。
 *
 * 背景：模板文案要能随模板内容一起从远端更新，而远端内容不可能有 `@StringRes`；
 * 因此模板的 `name` / `desc` 以及共享提示文案改由模板文件自身携带（方案 §6.2「正文外置」）。
 * App 自身的界面文案仍然走 `strings.xml`。
 *
 * key 为 BCP-47 语言标签，构造时统一小写（`en` / `zh` / `ru` / `ja` / `zh-hant` …）。
 * [resolve] 的回退链：完整标签 → 逐级去掉子标签 → [DEFAULT_LANGUAGE] → 第一个可用值，
 * 因此远端模板只提供部分语言时也能显示，不会出现空白项。
 */
class LocalizedText private constructor(private val values: Map<String, String>) {

    val isEmpty: Boolean get() = values.isEmpty()

    /** 已提供的语言标签（小写，保持声明顺序）。 */
    val languages: List<String> get() = values.keys.toList()

    /** 是否存在该语言的**非空**文案。 */
    fun has(language: String): Boolean = values[language.trim().lowercase()]?.isNotBlank() == true

    /** 按语言标签取文案；缺失返回 null（不做回退，供调用方自行决策）。 */
    fun textOrNull(language: String): String? =
        values[language.trim().lowercase()]?.takeIf { it.isNotBlank() }

    /** 按回退链取文案；完全缺失时返回空串（UI 侧用兜底文案展示）。 */
    fun resolve(languageTag: String?): String {
        if (values.isEmpty()) return ""
        val normalized = languageTag?.trim()?.lowercase().orEmpty()
        if (normalized.isNotEmpty()) {
            var candidate = normalized
            while (true) {
                textOrNull(candidate)?.let { return it }
                val cut = candidate.lastIndexOf('-')
                if (cut <= 0) break
                candidate = candidate.substring(0, cut)
            }
        }
        textOrNull(DEFAULT_LANGUAGE)?.let { return it }
        return values.values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    override fun equals(other: Any?): Boolean = other is LocalizedText && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "LocalizedText($values)"

    companion object {

        /** 文案缺失时的兜底语言。 */
        const val DEFAULT_LANGUAGE = "en"

        val EMPTY: LocalizedText = LocalizedText(emptyMap())

        /** 归一化语言键、丢弃空文案，并保持声明顺序（回退链依赖顺序的确定性）。 */
        fun of(values: Map<String, String>): LocalizedText {
            if (values.isEmpty()) return EMPTY
            val normalized = LinkedHashMap<String, String>(values.size)
            values.forEach { (language, text) ->
                val key = language.trim().lowercase()
                if (key.isNotEmpty() && text.isNotBlank()) normalized[key] = text
            }
            return if (normalized.isEmpty()) EMPTY else LocalizedText(normalized)
        }
    }
}
