package cn.yzapp.androidcontainer.feature.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 模板「使用」后要交回编排页的草稿（项目名 + YAML 正文）。 */
data class PendingDraft(val name: String, val yaml: String)

/**
 * 模板详情 → 编排编辑器的回传通道（方案 §6.3）。
 *
 * 刻意**不**把 YAML 塞进 `NavKey`（Navigation 3 的 key 会进序列化/日志），
 * 也不让模板库页面直接依赖编排 ViewModel 实例，而是用这个轻量 singleton 做一次性交接。
 */
object ComposeDraftBus {

    private val pending = MutableStateFlow<PendingDraft?>(null)

    val state: StateFlow<PendingDraft?> = pending.asStateFlow()

    /** 模板详情确认使用后写入；随后连续出栈回编排页。 */
    fun offer(draft: PendingDraft) {
        pending.value = draft
    }

    /** 编排页取走草稿（取走即清空，避免重复预填）。 */
    fun consume(): PendingDraft? = pending.value.also { pending.value = null }
}
