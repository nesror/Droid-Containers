package cn.yzapp.androidcontainer.feature.containers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 容器创建「进行中」标志（审查 P1-11）。
 *
 * 创建可能触发镜像拉取（分钟级），执行体挂了应用级作用域，与页面生命周期解耦；
 * 但页面 ViewModel 现在随导航条目出栈销毁，若只用页面内状态表示进行中，
 * 用户切走再回来会看到「按钮可点」→ 重复创建同一个容器。
 * 因此把进行中这一事实提到进程级：重建后的表单仍置灰按钮、仍显示拉取进度。
 *
 * 注意：表单字段本身仍是页面级状态（离开新建页即重置）——这是「切 tab 会清掉该 tab
 * 二级页」的既有导航语义，不是这里要解决的问题。
 */
internal object ContainerCreationOps {

    private val _working = MutableStateFlow(false)

    /** 创建进行中（含缺镜像时的自动拉取）。 */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    /** 取得创建资格；已在进行中返回 false（调用方忽略这次点击）。 */
    fun tryBegin(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        _working.value = true
        return true
    }

    fun end() {
        _working.value = false
        inFlight.set(false)
    }
}
