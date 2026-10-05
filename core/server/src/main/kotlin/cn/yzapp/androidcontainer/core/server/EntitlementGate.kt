package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.billing.EntitlementState

/**
 * 远程控制的解锁门禁（`docs/m8_m9_remote_control_plan.md` §7）。
 *
 * 定位：Web 控制台与 `/api/v1` 下的 REST 接口属于模板包（一次性内购），未解锁不可用。
 * **Docker 兼容实例不门禁**（面向 docker CLI 的通用能力，产品上仍免费）。
 *
 * 判定规则与 App 侧 `TemplatesViewModel.canUse()` 完全一致：**仅 `Unlocked` 放行**，
 * `Locked` 与 `Unknown` 都视为未解锁（fail-closed，宁可少给也不误放）。
 */
object EntitlementGate {

    /** 请求级判定：控制台与 REST 是否可用。 */
    fun isAllowed(state: EntitlementState): Boolean = state is EntitlementState.Unlocked

    /**
     * 服务级判定：Web 实例该启、该停、还是保持现状。
     *
     * `Unknown` 一律 [ServiceAction.KEEP] —— 首次查询尚未返回时既不启动也不停止，
     * 避免 Play 未就绪导致服务反复起停（与 App 侧「Unknown 不当作 Locked」的抖动防护同源）。
     */
    fun actionFor(state: EntitlementState, running: Boolean): ServiceAction = when (state) {
        is EntitlementState.Unlocked -> if (running) ServiceAction.KEEP else ServiceAction.START
        EntitlementState.Locked -> if (running) ServiceAction.STOP else ServiceAction.KEEP
        EntitlementState.Unknown -> ServiceAction.KEEP
    }

    /** 对外暴露的状态名（控制台按它做展示层翻译）。 */
    fun stateName(state: EntitlementState): String = when (state) {
        is EntitlementState.Unlocked -> "UNLOCKED"
        EntitlementState.Locked -> "LOCKED"
        EntitlementState.Unknown -> "UNKNOWN"
    }
}

enum class ServiceAction { START, STOP, KEEP }
