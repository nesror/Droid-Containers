package cn.yzapp.androidcontainer.core.engine.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回放缓冲（纯 Kotlin 部分，宿主 JVM 可跑；libpty 本身仅真机可用）。 */
class ReplayBufferTest {

    @Test
    fun `snapshot returns appended bytes in order`() {
        val buf = ReplayBuffer(1024)
        buf.append(byteArrayOf(1, 2, 3))
        buf.append(byteArrayOf(4, 5))
        assertEquals(byteArrayOf(1, 2, 3, 4, 5).toList(), buf.snapshot().toList())
    }

    @Test
    fun `append beyond limit drops oldest bytes`() {
        val buf = ReplayBuffer(4)
        buf.append(byteArrayOf(1, 2, 3))
        buf.append(byteArrayOf(4, 5, 6, 7, 8))
        val snap = buf.snapshot()
        assertEquals(4, snap.size)
        // 保留最新的 4 字节：5,6,7,8（1-4 被截断丢弃）
        assertEquals(byteArrayOf(5, 6, 7, 8).toList(), snap.toList())
    }

    @Test
    fun `snapshot is a copy not a live view`() {
        val buf = ReplayBuffer(16)
        buf.append(byteArrayOf(9))
        val snap1 = buf.snapshot()
        buf.append(byteArrayOf(8))
        assertTrue(snap1.size == 1 && buf.snapshot().size == 2)
    }
}
