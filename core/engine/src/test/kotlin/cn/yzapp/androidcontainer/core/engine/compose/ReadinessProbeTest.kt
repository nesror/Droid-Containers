package cn.yzapp.androidcontainer.core.engine.compose

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class ReadinessProbeTest {

    @Test
    fun `open port is detected`() {
        ServerSocket(0).use { server ->
            assertTrue(ReadinessProbe.isPortOpen(server.localPort))
        }
    }

    @Test
    fun `closed port is reported as not open`() {
        // 先拿端口再关闭（use 退出即 close），确保探测时无监听者
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(ReadinessProbe.isPortOpen(port))
    }

    @Test
    fun `awaitPortOpen returns the ready port`() = runBlocking {
        ServerSocket(0).use { server ->
            val ready = ReadinessProbe.awaitPortOpen(
                ports = listOf(server.localPort),
                timeoutMs = 2_000,
                intervalMs = 50,
            )
            assertEquals(server.localPort, ready)
        }
    }

    @Test
    fun `awaitPortOpen picks the first open port among candidates`() = runBlocking {
        ServerSocket(0).use { a ->
            ServerSocket(0).use { b ->
                val ready = ReadinessProbe.awaitPortOpen(
                    ports = listOf(a.localPort, b.localPort),
                    timeoutMs = 2_000,
                    intervalMs = 50,
                )
                assertNotNull(ready)
                assertTrue(ready == a.localPort || ready == b.localPort)
            }
        }
    }

    @Test
    fun `awaitPortOpen times out on closed ports`() = runBlocking {
        val start = System.currentTimeMillis()
        val ready = ReadinessProbe.awaitPortOpen(
            ports = listOf(1), // port 1 不可连
            timeoutMs = 300,
            intervalMs = 100,
        )
        assertNull(ready)
        assertTrue(System.currentTimeMillis() - start >= 300)
    }
}
