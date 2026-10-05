package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.model.ContainerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务直达的端口派生：容器端口 == 局域网可访问端口（proot 不做端口映射）。 */
class ServiceLinksTest {

    @Test
    fun `project plan reports the service count and ports in declaration order`() {
        val yaml = """
            services:
              web:
                image: nginx:alpine
                ports:
                  - "8081:8081"
              cache:
                image: redis:7-alpine
                ports:
                  - "6379:6379"
                  - "9000"
        """.trimIndent()

        val plan = ServiceLinks.planOfProject(yaml)
        assertEquals(2, plan.serviceCount)
        assertEquals(listOf(8081, 6379, 9000), plan.ports)
        assertFalse(plan.parseError)
    }

    @Test
    fun `protocol suffixes and duplicates are handled`() {
        val yaml = """
            services:
              dns:
                image: coredns/coredns:latest
                ports:
                  - "53:53/udp"
              web:
                image: nginx:alpine
                ports:
                  - "8080:8080"
                  - "8080:8080"
        """.trimIndent()

        assertEquals(listOf(53, 8080), ServiceLinks.planOfProject(yaml).ports)
    }

    @Test
    fun `a broken body is reported instead of throwing`() {
        val broken = ServiceLinks.planOfProject("services: [")
        assertTrue("the console must be able to tell the user the YAML is broken", broken.parseError)
        assertEquals(0, broken.serviceCount)
        assertEquals(emptyList<Int>(), broken.ports)
    }

    @Test
    fun `an empty body has no ports but is not a parse error`() {
        listOf(null, "").forEach { yaml ->
            val plan = ServiceLinks.planOfProject(yaml)
            assertEquals(emptyList<Int>(), plan.ports)
            assertEquals(0, plan.serviceCount)
        }
    }

    @Test
    fun `a service without ports simply reports none`() {
        val plan = ServiceLinks.planOfProject("services:\n  web:\n    image: nginx:alpine\n")
        assertEquals(1, plan.serviceCount)
        assertEquals(emptyList<Int>(), plan.ports)
        assertFalse(plan.parseError)
    }

    @Test
    fun `container http ports are merged and de-duplicated`() {
        val web = ContainerEntity(
            id = "web",
            name = "web",
            imageRef = "nginx:alpine",
            status = ContainerStatus.RUNNING,
            portsJson = "[8081,9000]",
        )
        val api = ContainerEntity(
            id = "api",
            name = "api",
            imageRef = "python:3.12-alpine",
            status = ContainerStatus.RUNNING,
            portsJson = "[9000,8000]",
        )

        assertEquals(listOf(8081, 9000, 8000), ServiceLinks.httpPortsOfContainers(listOf(web, api)))
        assertEquals(emptyList<Int>(), ServiceLinks.httpPortsOfContainers(emptyList()))
    }
}
