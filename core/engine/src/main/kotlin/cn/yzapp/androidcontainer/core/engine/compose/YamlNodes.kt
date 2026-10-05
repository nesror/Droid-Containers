package cn.yzapp.androidcontainer.core.engine.compose

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * kaml 节点访问的公共小工具。
 *
 * `ComposeParser`（compose 正文）与 `TemplateCatalog`（模板描述文件）共用同一套访问方式，
 * 避免两处各写一份适配层而逐渐漂移。
 */

/** 1 基行号；kaml 的 Location 行号从 1 开始。 */
internal fun YamlNode.line(): Int = location.line

internal fun YamlNode.column(): Int = location.column

internal fun YamlMap.node(key: String): YamlNode? =
    entries.entries.firstOrNull { it.key.content == key }?.value

internal fun YamlMap.scalar(key: String): YamlScalar? = node(key) as? YamlScalar

internal fun YamlNode.requireScalar(message: String): String =
    (this as? YamlScalar)?.content
        ?: throw ComposeParseException(message, line(), column())

/** 保持声明顺序的键列表（用于「未知键显式标记」）。 */
internal fun YamlMap.keys(): List<YamlScalar> = entries.keys.toList()

/** 保持声明顺序的键值对（`entries` 是属性名，故另起函数名避免歧义）。 */
internal fun YamlMap.pairs(): List<Pair<YamlScalar, YamlNode>> = entries.entries.map { it.key to it.value }
