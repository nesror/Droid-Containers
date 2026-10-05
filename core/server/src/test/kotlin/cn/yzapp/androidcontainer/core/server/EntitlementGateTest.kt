package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.billing.EntitlementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远程控制门禁：判定规则必须与 App 侧「仅 Unlocked 放行」完全一致。 */
class EntitlementGateTest {

    private val unlocked = EntitlementState.Unlocked(EntitlementState.Unlocked.Source.PLAY)

    @Test
    fun `only unlocked is allowed`() {
        assertTrue(EntitlementGate.isAllowed(unlocked))
        assertFalse("refunded or never purchased must not pass", EntitlementGate.isAllowed(EntitlementState.Locked))
        assertFalse(
            "an inconclusive state must fail closed for requests",
            EntitlementGate.isAllowed(EntitlementState.Unknown),
        )
    }

    @Test
    fun `unlocked starts the web service when it is not running and keeps it otherwise`() {
        assertEquals(ServiceAction.START, EntitlementGate.actionFor(unlocked, running = false))
        assertEquals(ServiceAction.KEEP, EntitlementGate.actionFor(unlocked, running = true))
    }

    @Test
    fun `locked stops a running web service but does not touch a stopped one`() {
        assertEquals(ServiceAction.STOP, EntitlementGate.actionFor(EntitlementState.Locked, running = true))
        assertEquals(ServiceAction.KEEP, EntitlementGate.actionFor(EntitlementState.Locked, running = false))
    }

    @Test
    fun `unknown never flips the service state`() {
        // Play 首次查询返回前的窄窗口：既不启动也不停止，避免服务反复起停
        assertEquals(ServiceAction.KEEP, EntitlementGate.actionFor(EntitlementState.Unknown, running = false))
        assertEquals(ServiceAction.KEEP, EntitlementGate.actionFor(EntitlementState.Unknown, running = true))
    }

    @Test
    fun `state names are stable tokens for the console to translate`() {
        assertEquals("UNLOCKED", EntitlementGate.stateName(unlocked))
        assertEquals("LOCKED", EntitlementGate.stateName(EntitlementState.Locked))
        assertEquals("UNKNOWN", EntitlementGate.stateName(EntitlementState.Unknown))
    }
}
