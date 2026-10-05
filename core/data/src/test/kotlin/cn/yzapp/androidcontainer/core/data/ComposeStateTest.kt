package cn.yzapp.androidcontainer.core.data

import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.model.ContainerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeStateTest {

    private fun container(id: String, service: String) = ContainerEntity(
        id = id,
        name = "stack-$service",
        imageRef = "alpine:3.20",
        status = ContainerStatus.RUNNING,
        projectId = "p1",
        serviceName = service,
    )

    @Test
    fun `no containers means project not created yet`() {
        assertEquals(
            ComposeProjectState.NOT_CREATED,
            composeProjectState(serviceCount = 2, projectContainers = emptyList(), runtime = emptyMap()),
        )
    }

    @Test
    fun `none running means stopped`() {
        val containers = listOf(container("a", "web"), container("b", "cache"))
        assertEquals(
            ComposeProjectState.STOPPED,
            composeProjectState(2, containers, mapOf("a" to ContainerRuntime.STOPPED)),
        )
    }

    @Test
    fun `all services running means running`() {
        val containers = listOf(container("a", "web"), container("b", "cache"))
        assertEquals(
            ComposeProjectState.RUNNING,
            composeProjectState(2, containers, containers.associate { it.id to ContainerRuntime.RUNNING }),
        )
    }

    @Test
    fun `partially running means partial`() {
        val containers = listOf(container("a", "web"), container("b", "cache"))
        assertEquals(
            ComposeProjectState.PARTIAL,
            composeProjectState(
                2,
                containers,
                mapOf("a" to ContainerRuntime.RUNNING, "b" to ContainerRuntime.STOPPED),
            ),
        )
    }

    @Test
    fun `fewer containers than services is partial even if all running`() {
        val containers = listOf(container("a", "web"))
        assertEquals(
            ComposeProjectState.PARTIAL,
            composeProjectState(2, containers, mapOf("a" to ContainerRuntime.RUNNING)),
        )
    }

    @Test
    fun `payload helpers round trip and tolerate corrupt data`() {
        assertEquals(
            listOf("redis-server", "--save", ""),
            ContainerPayloads.decodeList(ContainerPayloads.encodeList(listOf("redis-server", "--save", ""))),
        )
        val env = mapOf("TZ" to "Asia/Shanghai", "EMPTY" to "")
        assertEquals(env, ContainerPayloads.decodeMap(ContainerPayloads.encodeMap(env)))

        // 脏数据退化为空，而不是抛异常
        assertTrue(ContainerPayloads.decodeList("{not json").isEmpty())
        assertTrue(ContainerPayloads.decodeMap(null).isEmpty())
        assertTrue(ContainerPayloads.commandOf(container("a", "web")).isEmpty())
    }
}
