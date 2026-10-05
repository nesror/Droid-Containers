package cn.yzapp.androidcontainer.core.engine.oci

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.HostAbi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

class OciRegistryClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 极简 registry 模拟：JDK 内置 HttpServer，按路径分发。 */
    private class FakeRegistry(private val indexJson: String = DEFAULT_INDEX) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = Collections.synchronizedList(mutableListOf<String>())

        private val manifestIndex: String = indexJson

        val layerContent = "layer1"
        val layerDigest = "sha256:" +
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(layerContent.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private val arm64Manifest get() = """
            {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
             "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"sha256:cfg","size":100},
             "layers":[
               {"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$layerDigest","size":6}
             ]}
        """.trimIndent()

        companion object {
            val DEFAULT_INDEX = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json",
                 "manifests":[
                   {"digest":"sha256:aaa","mediaType":"application/vnd.oci.image.manifest.v1+json",
                    "platform":{"os":"linux","architecture":"arm64"}},
                   {"digest":"sha256:bbb","mediaType":"application/vnd.oci.image.manifest.v1+json",
                    "platform":{"os":"linux","architecture":"amd64"}}
                 ]}
            """.trimIndent()

            /** 仅含 amd64 平台的 index（验证不匹配平台报 manifestUnsupported）。 */
            val AMD64_ONLY_INDEX = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json",
                 "manifests":[
                   {"digest":"sha256:bbb","mediaType":"application/vnd.oci.image.manifest.v1+json",
                    "platform":{"os":"linux","architecture":"amd64"}}
                 ]}
            """.trimIndent()
        }

        fun start() {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val query = exchange.requestURI.rawQuery ?: ""
                requests.add("$path?$query")
                respond(exchange, when {
                    path == "/v2/library/alpine/manifests/latest" ->
                        if (exchange.requestHeaders.getFirst("Authorization") == null) {
                            respond(
                                exchange, 401, "",
                                "WWW-Authenticate" to
                                    """Bearer realm="http://auth.docker.io/token",service="registry.docker.io",scope="repository:library/alpine:pull"""",
                            )
                            return@createContext
                        } else {
                            200 to manifestIndex
                        }
                    path == "/v2/library/alpine/manifests/sha256:aaa" ->
                        200 to arm64Manifest
                    path == "/token" -> {
                        // realm 改写验证：上游 auth.docker.io 的 realm 应被改写为本地 host
                        assertTrue("token 请求应携带 scope", query.contains("scope=repository%3Alibrary%2Falpine%3Apull"))
                        200 to """{"token":"test-token"}"""
                    }
                    path == "/v2/library/alpine/blobs/$layerDigest" -> 200 to layerContent
                    else -> 404 to "not found"
                })
            }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun respond(exchange: HttpExchange, pair: Pair<Int, String>) {
            val (code, body) = pair
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (code == 401 || bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }

        private fun respond(exchange: HttpExchange, code: Int, body: String, vararg headers: Pair<String, String>) {
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(code, -1L)
            exchange.close()
        }
    }

    private fun clientFor(registry: FakeRegistry) =
        OciRegistryClient(
            host = "127.0.0.1:${registry.port}",
            httpClient = OkHttpClient.Builder().build(),
            scheme = "http",
        )

    @Test
    fun `resolve walks 401 token then selects arm64 manifest from index`() = runBlocking {
        val registry = FakeRegistry()
        registry.start()
        try {
            val image = clientFor(registry).resolve(ImageRef.parse("alpine"), HostAbi.ARM64)
            assertEquals("library/alpine", image.repo)
            assertEquals("sha256:aaa", image.manifestDigest)
            assertEquals("sha256:cfg", image.configDigest)
            assertEquals(1, image.layers.size)
            assertEquals(registry.layerDigest, image.layers[0].digest)
            assertEquals(6L, image.layers[0].size)
            // 触发了 token 流程且 realm 被改写到本地 host
            assertTrue(registry.requests.any { it.startsWith("/token?") })
            assertTrue(registry.requests.none { it.startsWith("https://auth.docker.io") })
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `resolve rejects unsupported platform manifest`() = runBlocking {
        val registry = FakeRegistry(FakeRegistry.AMD64_ONLY_INDEX)
        registry.start()
        try {
            try {
                clientFor(registry).resolve(ImageRef.parse("alpine"), HostAbi.ARM64)
                org.junit.Assert.fail("expected MANIFEST_UNSUPPORTED")
            } catch (e: EngineException) {
                assertEquals(EngineErrorCode.MANIFEST_UNSUPPORTED, e.code)
            }
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `blob download streams body to destination file`() = runBlocking {
        val registry = FakeRegistry()
        registry.start()
        try {
            val dest = File(tmp.root, "layer1.tar.gz")
            var reported = -1L
            clientFor(registry).downloadBlob(ImageRef.parse("alpine"), registry.layerDigest, dest) { done, _ ->
                reported = done
            }
            assertEquals("layer1", dest.readText())
            assertEquals(6L, reported)
            assertTrue(!File(tmp.root, "layer1.tar.gz.part").exists())
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `parse handles registry host, repo and tag combinations`() {
        assertEquals(ImageRef(null, "alpine", "latest"), ImageRef.parse("alpine"))
        assertEquals(ImageRef(null, "alpine", "3.20"), ImageRef.parse("alpine:3.20"))
        assertEquals("library/alpine", ImageRef.parse("alpine:3.20").apiRepo)
        assertEquals(ImageRef(null, "user/app", "v1"), ImageRef.parse("user/app:v1"))
        assertEquals(
            ImageRef("registry.example.com", "team/app", "latest"),
            ImageRef.parse("registry.example.com/team/app"),
        )
        assertEquals(
            ImageRef("registry.example.com:5000", "team/app", "5000-tag"),
            ImageRef.parse("registry.example.com:5000/team/app:5000-tag"),
        )
        assertEquals("library/alpine", ImageRef.parse("alpine").apiRepo)
        assertEquals("user/app", ImageRef.parse("user/app").apiRepo)
    }

    @Test
    fun `parse rejects blank reference`() {
        try {
            ImageRef.parse("  ")
            org.junit.Assert.fail("expected EngineException")
        } catch (e: EngineException) {
            // MANIFEST_UNSUPPORTED 预期
        }
    }
}
