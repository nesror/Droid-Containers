package cn.yzapp.androidcontainer.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨页面可见的操作失败提示（审查 P1-11）。
 *
 * 镜像拉取、容器启停、编排 up 这类分钟级长任务已从 `viewModelScope` 迁到应用级作用域
 * （[DataGraph.appScope]）——因为页面 ViewModel 现在随导航条目出栈销毁，任务失败时
 * 发起它的页面可能已经不在了，错误若写进页面私有状态就会静默丢失（用户只看到"没反应"）。
 *
 * 这里用一条进程级消息承载「最近一次操作失败」，任何可见页面都能弹出并关闭它：
 * 同一时刻只保留最近一条，先看到先处理——与 docker CLI 的错误语义一致
 * （错误是给用户的提示，不是审计流水；审计走 [RemoteAuditLog]）。
 */
object OpsErrors {

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    fun report(message: String?) {
        _message.value = message
    }

    fun clear() {
        _message.value = null
    }
}
