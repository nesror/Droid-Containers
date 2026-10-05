package cn.yzapp.androidcontainer.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerRunParserTest {

    @Test
    fun `parses bare image`() {
        val spec = DockerRunParser.parse("docker run alpine:3.20")
        assertEquals("alpine:3.20", spec!!.image)
        assertNull(spec.name)
        assertFalse(spec.detached)
        assertTrue(spec.entryCommand.isEmpty())
        assertTrue(spec.unsupportedOptions.isEmpty())
    }

    @Test
    fun `parses name detach and command`() {
        val spec = DockerRunParser.parse(
            "docker run -d --name web nginx:latest sh -c \"echo hi && sleep 1d\"",
        )
        assertEquals("nginx:latest", spec!!.image)
        assertEquals("web", spec.name)
        assertTrue(spec.detached)
        assertEquals(listOf("sh", "-c", "echo hi && sleep 1d"), spec.entryCommand)
    }

    @Test
    fun `parses equals form of name`() {
        val spec = DockerRunParser.parse("docker run --name=db busybox")
        assertEquals("db", spec!!.name)
        assertEquals("busybox", spec.image)
    }

    @Test
    fun `consumes option values before image`() {
        val spec = DockerRunParser.parse("docker run -p 8080:80 -e FOO=bar --name app -v /data:/d redis")
        assertEquals("redis", spec!!.image)
        assertEquals("app", spec.name)
        // 阶段一不支持端口/环境变量/挂载 → 全部进入 unsupported
        assertEquals(listOf("-p", "-e", "-v"), spec.unsupportedOptions)
    }

    @Test
    fun `long flags collect unsupported`() {
        val spec = DockerRunParser.parse(
            "docker run --rm --network host --restart always --name svc alpine",
        )
        assertEquals("alpine", spec!!.image)
        assertEquals(listOf("--rm", "--network", "--restart"), spec.unsupportedOptions)
    }

    @Test
    fun `accepts docker container run and sudo prefix`() {
        assertEquals("alpine", DockerRunParser.parse("docker container run alpine")!!.image)
        assertEquals("alpine", DockerRunParser.parse("sudo docker run alpine")!!.image)
    }

    @Test
    fun `double dash passes rest to container command`() {
        val spec = DockerRunParser.parse("docker run alpine -- --flag value")
        assertEquals("alpine", spec!!.image)
        assertEquals(listOf("--flag", "value"), spec.entryCommand)
    }

    @Test
    fun `returns null for non docker run input`() {
        assertNull(DockerRunParser.parse("docker ps"))
        assertNull(DockerRunParser.parse("docker run"))
        assertNull(DockerRunParser.parse("hello world"))
        assertNull(DockerRunParser.parse("docker run -d --name web"))
    }

    @Test
    fun `quote aware tokenizer keeps spaces in values`() {
        val spec = DockerRunParser.parse("docker run --name 'my app' busybox echo 'a b'")
        assertEquals("my app", spec!!.name)
        assertEquals(listOf("echo", "a b"), spec.entryCommand)
    }

    @Test
    fun `combined short flags`() {
        val spec = DockerRunParser.parse("docker run -it -d alpine sh")
        assertTrue(spec!!.detached)
        assertEquals(listOf("sh"), spec.entryCommand)
    }
}
