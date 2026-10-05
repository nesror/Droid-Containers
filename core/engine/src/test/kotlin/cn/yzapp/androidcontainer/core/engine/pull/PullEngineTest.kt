package cn.yzapp.androidcontainer.core.engine.pull

import cn.yzapp.androidcontainer.core.common.DefaultDispatcherProvider
import cn.yzapp.androidcontainer.core.engine.tar.TarTestSupport
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.HostAbi
import cn.yzapp.androidcontainer.core.model.PullProgress
import cn.yzapp.androidcontainer.core.model.PullStage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.zip.GZIPOutputStream

class PullEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRegistry(
        private val manifestBehavior: (HttpExchange) -> Unit,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private var tokenIssued = false

        fun blobRequests(): Int = requests.count { it.startsWith("/v2/library/alpine/blobs/") }

        fun start() {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val query = exchange.requestURI.rawQuery ?: ""
                requests.add("$path?$query")
                when {
                    path.contains("/manifests/") -> manifestBehavior(exchange)
                    path == "/token" -> {
                        tokenIssued = true
                        reply(exchange, 200, """{"token":"t"}""")
                    }
                    path.contains("/blobs/$LAYER_DIGEST") -> {
                        // gzip 压缩的 tar：etc/os-release = "alpine\n"
                        replyBytes(exchange, 200, gzippedTar())
                    }
                    path.contains("/blobs/$CONFIG_DIGEST") -> {
                        reply(exchange, 200, configJson())
                    }
                    else -> reply(exchange, 404, "nf")
                }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun reply(exchange: HttpExchange, code: Int, body: String, vararg headers: Pair<String, String>) {
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }

        private fun replyBytes(exchange: HttpExchange, code: Int, bytes: ByteArray) {
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }

        companion object {
            private val tarBytes: ByteArray by lazy {
                TarTestSupport.tarBytes(
                    listOf(
                        TarTestSupport.dir("etc"),
                        TarTestSupport.file("etc/os-release", "alpine\n"),
                    ),
                )
            }

            val layerBlob: ByteArray by lazy {
                ByteArrayOutputStream().also { out ->
                    GZIPOutputStream(out).use { it.write(tarBytes) }
                }.toByteArray()
            }

            private fun gzippedTar(): ByteArray = layerBlob

            /** blob 内容的真实 sha256（下载端 digest 校验开启后，伪造 digest 会直接失败）。 */
            private fun sha256(bytes: ByteArray): String =
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }

            val LAYER_DIGEST: String by lazy { "sha256:" + sha256(layerBlob) }
            val CONFIG_DIGEST: String by lazy { "sha256:" + sha256(configJson().toByteArray()) }

            fun manifestJson(): String {
                val size = layerBlob.size
                return """
                    {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
                     "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$CONFIG_DIGEST","size":1},
                     "layers":[
                       {"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$LAYER_DIGEST","size":$size}
                     ]}
                """.trimIndent()
            }

            fun configJson(): String = """
                {"architecture":"arm64",
                 "config":{"Entrypoint":["/docker-entrypoint.sh"],"Cmd":["nginx","-g","daemon off;"]}}
            """.trimIndent()

            fun indexJson(): String = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json",
                 "manifests":[
                   {"digest":"sha256:aaa","mediaType":"application/vnd.oci.image.manifest.v1+json",
                    "platform":{"os":"linux","architecture":"arm64"}}
                 ]}
            """.trimIndent()
        }
    }

    private fun brokenManifest(exchange: HttpExchange) {
        val code = 500
        exchange.sendResponseHeaders(code, -1)
        exchange.close()
    }

    private fun healthyManifest(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (exchange.requestHeaders.getFirst("Authorization") == null) {
            exchange.responseHeaders.add(
                "WWW-Authenticate",
                """Bearer realm="http://auth.upstream.example/token",service="registry.docker.io"""",
            )
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        val body = if (path.endsWith("/manifests/latest")) FakeRegistry.indexJson() else FakeRegistry.manifestJson()
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test
    fun `falls back to second mirror, downloads, extracts and reaches READY`() = runTest {
        val broken = FakeRegistry(::brokenManifest)
        val healthy = FakeRegistry(::healthyManifest)
        broken.start(); healthy.start()
        val engineDir = tmp.newFolder("engine")

        try {
            val engine = PullEngine(
                mirrors = listOf("127.0.0.1:${broken.port}", "127.0.0.1:${healthy.port}"),
                engineDir = engineDir,
                hostAbi = HostAbi.ARM64,
                dispatchers = DefaultDispatcherProvider(),
                scheme = "http",
            )
            val rootfs = engine.pull("alpine")

            assertTrue(File(rootfs, "etc/os-release").readText() == "alpine\n")
            // 镜像 config blob 已落盘到 rootfs 同级（启动时回落镜像默认入口用）
            val configFile = File(rootfs.parentFile, "config")
            assertTrue(configFile.isFile)
            assertTrue(configFile.readText().contains("docker-entrypoint.sh"))
            val last = engine.progress.first()
            assertEquals(PullStage.READY, last.stage)
            assertEquals("127.0.0.1:${healthy.port}", last.mirrorHost)
            // 失败源被跳过（其请求曾发生 500）
            assertTrue(broken.requests.isNotEmpty())
            // realm 改写：token 请求打到健康源，而非上游域
            assertTrue(healthy.requests.any { it.startsWith("/token?") })
        } finally {
            broken.stop(); healthy.stop()
        }
    }

    @Test
    fun `progress carries layer index, completed layers and overall bytes`() = runTest {
        val healthy = FakeRegistry(::healthyManifest)
        healthy.start()
        val engineDir = tmp.newFolder("engine")

        try {
            val engine = PullEngine(
                mirrors = listOf("127.0.0.1:${healthy.port}"),
                engineDir = engineDir,
                hostAbi = HostAbi.ARM64,
                dispatchers = DefaultDispatcherProvider(),
                scheme = "http",
            )
            val seen = mutableListOf<PullProgress>()
            val collector = launch { engine.progress.collect { seen.add(it) } }
            engine.pull("alpine")
            collector.cancel()

            val layerSize = FakeRegistry.layerBlob.size.toLong()
            val downloading = seen.filter { it.stage == PullStage.DOWNLOADING }
            assertTrue(downloading.isNotEmpty())
            // 单层镜像：正在下载第 1/1 层，已完成 0 层，累计总量 = 层大小
            assertTrue(downloading.all { it.totalLayers == 1 && it.currentLayer == 1 })
            assertTrue(downloading.all { it.completedLayers == 0 })
            assertTrue(downloading.all { it.overallTotalBytes == layerSize })
            assertTrue(downloading.all { it.overallDownloadedBytes == it.downloadedBytes })

            val extracting = seen.filter { it.stage == PullStage.EXTRACTING }
            assertTrue(extracting.isNotEmpty())
            // 层下载完成后才进入解压：已完成层与累计字节都已计入
            assertTrue(extracting.all { it.completedLayers == 1 })
            assertTrue(extracting.all { it.overallDownloadedBytes == layerSize })
        } finally {
            healthy.stop()
        }
    }

    @Test
    fun `second pull hits blob cache and skips blob download`() = runTest {
        val healthy = FakeRegistry(::healthyManifest)
        healthy.start()
        val engineDir = tmp.newFolder("engine")

        try {
            val engine = PullEngine(
                mirrors = listOf("127.0.0.1:${healthy.port}"),
                engineDir = engineDir,
                hostAbi = HostAbi.ARM64,
                dispatchers = DefaultDispatcherProvider(),
                scheme = "http",
            )
            engine.pull("alpine")
            val blobRequestsFirst = healthy.blobRequests()
            assertTrue(blobRequestsFirst >= 1)

            engine.pull("alpine")
            val blobRequestsSecond = healthy.blobRequests()
            assertEquals(blobRequestsFirst, blobRequestsSecond)
        } finally {
            healthy.stop()
        }
    }

    @Test
    fun `rootfs path is deterministic per image ref`() {
        val engineDir = tmp.newFolder("engine")
        val engine = PullEngine(
            mirrors = listOf("x"),
            engineDir = engineDir,
            hostAbi = HostAbi.ARM64,
            dispatchers = DefaultDispatcherProvider(),
        )
        val a = engine.rootfsDirFor(cn.yzapp.androidcontainer.core.engine.oci.ImageRef.parse("alpine:3.20"))
        val b = engine.rootfsDirFor(cn.yzapp.androidcontainer.core.engine.oci.ImageRef.parse("alpine:3.20"))
        val c = engine.rootfsDirFor(cn.yzapp.androidcontainer.core.engine.oci.ImageRef.parse("user/app:1.0"))
        assertEquals(a.path, b.path)
        assertTrue(a.path != c.path)
        assertTrue(a.path.startsWith(File(engineDir, "layers").path))
    }

    @Test
    fun `all mirrors failing surfaces last engine exception`() = runTest {
        val broken1 = FakeRegistry(::brokenManifest)
        val broken2 = FakeRegistry(::brokenManifest)
        broken1.start(); broken2.start()
        try {
            val engine = PullEngine(
                mirrors = listOf("127.0.0.1:${broken1.port}", "127.0.0.1:${broken2.port}"),
                engineDir = tmp.newFolder("engine"),
                hostAbi = HostAbi.ARM64,
                dispatchers = DefaultDispatcherProvider(),
                scheme = "http",
            )
            try {
                engine.pull("alpine")
                org.junit.Assert.fail("expected EngineException")
            } catch (e: EngineException) {
                assertEquals(cn.yzapp.androidcontainer.core.model.EngineErrorCode.MIRROR_UNAVAILABLE, e.code)
            }
        } finally {
            broken1.stop(); broken2.stop()
        }
    }
}
