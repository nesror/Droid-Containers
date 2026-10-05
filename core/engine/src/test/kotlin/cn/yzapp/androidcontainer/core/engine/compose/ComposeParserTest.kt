package cn.yzapp.androidcontainer.core.engine.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeParserTest {

    // ------------------------------------------------------------ 基础解析

    @Test
    fun `parses services in declaration order with image and list command`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                command: ["nginx", "-g", "daemon off;"]
              cache:
                image: redis:7-alpine
            """.trimIndent(),
        )

        assertEquals(listOf("web", "cache"), spec.serviceNames)
        assertEquals("nginx:alpine", spec.service("web")!!.image)
        assertEquals(listOf("nginx", "-g", "daemon off;"), spec.service("web")!!.command)
        assertTrue(!spec.service("web")!!.commandShellForm)
    }

    @Test
    fun `shell form command resolves through bin sh`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                command: echo hello && sleep 1
            """.trimIndent(),
        )

        val service = spec.service("web")!!
        assertTrue(service.commandShellForm)
        assertEquals(listOf("/bin/sh", "-c", "echo hello && sleep 1"), service.resolvedArgv())
    }

    @Test
    fun `entrypoint overrides and command is appended as argument`() {
        val spec = ComposeParser.parse(
            """
            services:
              app:
                image: alpine:3.20
                entrypoint: ["/usr/bin/app"]
                command: ["--flag"]
            """.trimIndent(),
        )

        assertEquals(listOf("/usr/bin/app", "--flag"), spec.service("app")!!.resolvedArgv())
    }

    @Test
    fun `parses environment in both map and list form`() {
        val spec = ComposeParser.parse(
            """
            services:
              a:
                image: alpine:3.20
                environment:
                  TZ: Asia/Shanghai
                  EMPTY:
                  DEBUG: true
              b:
                image: alpine:3.20
                environment:
                  - FOO=bar=baz
                  - ONLY_KEY
            """.trimIndent(),
        )

        assertEquals(
            mapOf("TZ" to "Asia/Shanghai", "EMPTY" to "", "DEBUG" to "true"),
            spec.service("a")!!.environment,
        )
        assertEquals(mapOf("FOO" to "bar=baz", "ONLY_KEY" to ""), spec.service("b")!!.environment)
        // 只写变量名（引用宿主环境不可用）必须显式提示
        assertTrue(spec.issues.any { it.key == "environment" && it.service == "b" })
    }

    @Test
    fun `parses ports and volumes and flags them as hints`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                ports:
                  - "8080:80"
                volumes:
                  - data:/var/lib/data
            """.trimIndent(),
        )

        assertEquals(listOf("8080:80"), spec.service("web")!!.ports)
        assertEquals(listOf("data:/var/lib/data"), spec.service("web")!!.volumes)
        // named volume 现已挂载（volumeMounts），不再整组报 VOLUMES_IGNORED
        assertEquals(listOf(VolumeMount("data", "/var/lib/data")), spec.service("web")!!.volumeMounts)
        assertTrue(spec.issues.none { it.kind == ComposeIssueKind.VOLUMES_IGNORED })
        assertTrue(spec.issues.any { it.kind == ComposeIssueKind.PORTS_HINT } )
    }

    // ------------------------------------------------------------ restart 策略

    @Test
    fun `restart scalar policies are parsed`() {
        val spec = ComposeParser.parse(
            """
            services:
              a:
                image: alpine:3.20
                restart: always
              b:
                image: alpine:3.20
                restart: unless-stopped
              c:
                image: alpine:3.20
                restart: "no"
              d:
                image: alpine:3.20
                restart: on-failure
            """.trimIndent(),
        )

        assertEquals("always", spec.service("a")!!.restartPolicy)
        assertEquals("unless-stopped", spec.service("b")!!.restartPolicy)
        assertEquals("no", spec.service("c")!!.restartPolicy)
        assertEquals("on-failure", spec.service("d")!!.restartPolicy)
        assertTrue(spec.issues.isEmpty())
    }

    @Test
    fun `restart absent is null`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
            """.trimIndent(),
        )

        assertNull(spec.service("web")!!.restartPolicy)
        assertTrue(spec.issues.isEmpty())
    }

    @Test
    fun `restart long syntax takes policy and flags other keys`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                restart:
                  policy: always
                  delay: 5s
            """.trimIndent(),
        )

        assertEquals("always", spec.service("web")!!.restartPolicy)
        val unsupported = spec.issues.filter { it.kind == ComposeIssueKind.UNSUPPORTED_KEY }
        assertTrue(unsupported.any { it.service == "web" && it.key == "restart" && "\"delay\"" in it.message })
    }

    @Test
    fun `unknown restart value is reported and treated as no`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                restart: sometimes
            """.trimIndent(),
        )

        assertNull(spec.service("web")!!.restartPolicy)
        assertTrue(spec.issues.any { it.service == "web" && it.key == "restart" && "\"sometimes\"" in it.message })
    }

    @Test
    fun `unsupported service and root keys are reported instead of dropped silently`() {
        val spec = ComposeParser.parse(
            """
            version: "3.9"
            networks:
              default:
                driver: bridge
            services:
              web:
                image: nginx:alpine
                build: .
                healthcheck:
                  test: ["CMD", "true"]
            """.trimIndent(),
        )

        assertEquals(1, spec.services.size)
        val unsupported = spec.issues.filter { it.kind == ComposeIssueKind.UNSUPPORTED_KEY }
        assertTrue(unsupported.any { it.service == "web" && it.key == "build" })
        assertTrue(unsupported.any { it.service == "web" && it.key == "healthcheck" })
        assertTrue(unsupported.any { it.service == null && it.key == "networks" })
        // version 是受支持键（忽略但不报）
        assertTrue(unsupported.none { it.key == "version" })
    }

    // ------------------------------------------------------------ 依赖与顺序

    @Test
    fun `startup order follows depends_on and shutdown reverses it`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                depends_on:
                  - cache
              cache:
                image: redis:7-alpine
              log:
                image: alpine:3.20
            """.trimIndent(),
        )

        assertEquals(listOf("cache", "log"), spec.startupOrder().first())
        assertEquals(listOf("web"), spec.startupOrder()[1])
        assertEquals(listOf("cache", "log", "web"), spec.startupSequence())
        assertEquals(listOf("web", "log", "cache"), spec.shutdownSequence())
    }

    @Test
    fun `depends_on long syntax keeps keys and reports condition as issue`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                depends_on:
                  cache:
                    condition: service_healthy
              cache:
                image: redis:7-alpine
            """.trimIndent(),
        )

        assertEquals(listOf("cache"), spec.service("web")!!.dependsOn)
        assertTrue(spec.issues.any { it.kind == ComposeIssueKind.DEPENDS_ON_CONDITION })
    }

    @Test
    fun `pid1 entrypoint is flagged`() {
        val spec = ComposeParser.parse(
            """
            services:
              ha:
                image: homeassistant/home-assistant:stable
                entrypoint: /init
            """.trimIndent(),
        )

        assertTrue(spec.issues.any { it.kind == ComposeIssueKind.PID1_RISK })
    }

    // ------------------------------------------------------------ 致命错误

    @Test
    fun `same container port in two services fails as port conflict`() {
        val error = parseFailure(
            """
            services:
              web:
                image: nginx:alpine
                ports: ["8080:8080"]
              api:
                image: alpine:3.20
                ports: ["8080"]
            """.trimIndent(),
        )
        assertTrue(error.message!!.contains("port conflict"))
        assertTrue(error.message!!.contains("web"))
        assertTrue(error.message!!.contains("api"))
        assertEquals(5, error.line)
    }

    @Test
    fun `different host mappings to the same container port conflict`() {
        // proot 不做端口映射：容器端口 80 就是手机端口，两条宿主映射救不了
        val error = parseFailure(
            """
            services:
              a:
                image: nginx:alpine
                ports: ["8080:80"]
              b:
                image: nginx:alpine
                ports: ["8081:80"]
            """.trimIndent(),
        )
        assertTrue(error.message!!.contains("port conflict"))
    }

    @Test
    fun `same port with different protocols does not conflict`() {
        val spec = ComposeParser.parse(
            """
            services:
              dns-tcp:
                image: alpine:3.20
                ports: ["53:53/tcp"]
              dns-udp:
                image: alpine:3.20
                ports: ["53:53/udp"]
            """.trimIndent(),
        )
        assertEquals(2, spec.services.size)
    }

    @Test
    fun `duplicate port declaration within one service does not conflict`() {
        val spec = ComposeParser.parse(
            """
            services:
              web:
                image: nginx:alpine
                ports: ["8080", "8080:8080"]
            """.trimIndent(),
        )
        assertEquals(1, spec.services.size)
    }

    // ------------------------------------------------------------ volumes 挂载

    @Test
    fun `named volume produces mount and no issue`() {
        val spec = ComposeParser.parse(
            """
            volumes:
              dbdata:
                driver: local
            services:
              db:
                image: alpine:3.20
                volumes: ["dbdata:/var/lib/db"]
            """.trimIndent(),
        )
        assertEquals(listOf("dbdata"), spec.declaredVolumes)
        assertEquals(listOf(VolumeMount("dbdata", "/var/lib/db")), spec.service("db")!!.volumeMounts)
        assertTrue(spec.issues.none { it.service == "db" && it.key == "volumes" })
    }

    @Test
    fun `implicit named volume without top level declaration is still mounted`() {
        // 对齐容错哲学：直接丢一份 compose 进来也能用，不强制顶层声明
        val spec = ComposeParser.parse(
            """
            services:
              db:
                image: alpine:3.20
                volumes: ["appdata:/data"]
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), spec.declaredVolumes)
        assertEquals(listOf(VolumeMount("appdata", "/data")), spec.service("db")!!.volumeMounts)
    }

    @Test
    fun `host path volume is reported as ignored for security`() {
        val spec = ComposeParser.parse(
            """
            services:
              db:
                image: alpine:3.20
                volumes: ["/sdcard/x:/data"]
            """.trimIndent(),
        )
        assertTrue(spec.issues.any { it.kind == ComposeIssueKind.VOLUME_BIND_IGNORED })
        assertTrue(spec.service("db")!!.volumeMounts.isEmpty())
    }

    @Test
    fun `anonymous volume stays as ignored hint`() {
        val spec = ComposeParser.parse(
            """
            services:
              db:
                image: alpine:3.20
                volumes: ["/var/lib/data"]
            """.trimIndent(),
        )
        assertTrue(spec.issues.any { it.kind == ComposeIssueKind.VOLUMES_IGNORED })
        assertTrue(spec.service("db")!!.volumeMounts.isEmpty())
    }

    @Test
    fun `missing image fails with line number of the service`() {
        val error = parseFailure(
            """
            services:
              web:
                command: echo hi
            """.trimIndent(),
        )
        assertTrue(error.message!!.contains("missing image"))
        assertEquals(2, error.line)
    }

    @Test
    fun `unknown dependency fails`() {
        val error = parseFailure(
            """
            services:
              web:
                image: nginx:alpine
                depends_on:
                  - db
            """.trimIndent(),
        )
        assertTrue(error.message!!.contains("db"))
    }

    @Test
    fun `dependency cycle fails`() {
        val error = parseFailure(
            """
            services:
              a:
                image: alpine:3.20
                depends_on: [b]
              b:
                image: alpine:3.20
                depends_on: [a]
            """.trimIndent(),
        )
        assertTrue(error.message!!.contains("cycle"))
    }

    @Test
    fun `missing services section fails`() {
        val error = parseFailure("version: \"3.9\"\n")
        assertTrue(error.message!!.contains("services"))
    }

    @Test
    fun `blank document fails`() {
        assertNotNull(parseFailure(""))
        assertNotNull(parseFailure("   \n"))
    }

    @Test
    fun `malformed yaml reports line number`() {
        val error = parseFailure(
            """
            services:
              web:
                image: "nginx:alpine
            """.trimIndent(),
        )
        assertNotNull(error.line)
        assertTrue(error.line!! > 0)
    }

    // ------------------------------------------------------------ 示例与命名

    @Test
    fun `built-in sample parses without fatal error`() {
        val spec = ComposeParser.parse(ComposeSamples.REDIS_WEB)
        assertEquals(listOf("cache", "web"), spec.serviceNames)
        assertEquals(listOf("cache", "web"), spec.startupSequence())
    }

    @Test
    fun `container name follows project-service and is sanitized`() {
        assertEquals("my-stack-web", ComposeNaming.containerName("My Stack", "web"))
        assertEquals("proj-a-1", ComposeNaming.containerName("proj", "a 1"))
        assertNull(ComposeSpec(emptyList()).service("nope"))
    }

    private fun parseFailure(yaml: String): ComposeParseException = try {
        ComposeParser.parse(yaml)
        throw AssertionError("expected ComposeParseException for:\n$yaml")
    } catch (e: ComposeParseException) {
        e
    }
}
