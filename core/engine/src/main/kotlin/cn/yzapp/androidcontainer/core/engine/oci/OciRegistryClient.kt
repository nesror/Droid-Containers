package cn.yzapp.androidcontainer.core.engine.oci

import cn.yzapp.androidcontainer.core.model.HostAbi
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.engine.tar.LayerFormat
import cn.yzapp.androidcontainer.core.engine.tar.layerFormatFromMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

/** 解析后的镜像清单（方案 §3.2）。 */
data class ResolvedImage(
    val repo: String,
    val manifestDigest: String,
    val configDigest: String,
    val layers: List<Layer>,
    val resolvedHost: String,
)

data class Layer(
    val digest: String,
    val size: Long,
    val mediaType: String,
    val format: LayerFormat,
)

/**
 * Registry V2 客户端（单镜像源）：manifest list → 按 ABI 选平台 → 层清单。
 * 鉴权按 WWW-Authenticate 换匿名 token；镜像源的 realm 若指向上游主机
 * 则改写为当前镜像源主机（方案 §3.2，大陆网络下上游不可达）。
 * 多镜像源回退由上层 PullEngine 编排。
 */
class OciRegistryClient(
    private val host: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
    /** 默认 https；测试环境注入 http 以便本地模拟 registry。 */
    private val scheme: String = "https",
) {

    private var cachedToken: String? = null

    /** host 可能带端口，统一解析一次供 realm 改写使用。 */
    private val baseUrl: okhttp3.HttpUrl = "$scheme://$host".toHttpUrl()

    suspend fun resolve(ref: ImageRef, hostAbi: HostAbi): ResolvedImage = withContext(Dispatchers.IO) {
        val root = Json.parseToJsonElement(fetchManifest(ref, ref.tag)).jsonObject

        val manifest: kotlinx.serialization.json.JsonObject
        val manifestDigest: String
        if (root.containsKey("manifests")) {
            // index / manifest list：按宿主 ABI 选择平台
            manifestDigest = selectPlatform(root, hostAbi)
            manifest = Json.parseToJsonElement(fetchManifest(ref, manifestDigest)).jsonObject
        } else {
            manifest = root
            manifestDigest = "tag:${ref.tag}"
        }

        val config = manifest["config"]?.jsonObject
            ?: throw manifestError(ref, "manifest missing config")
        val layersJson = manifest["layers"]?.jsonArray
            ?: throw manifestError(ref, "manifest missing layers")

        val layers = layersJson.map { el ->
            val obj = el.jsonObject
            val mediaType = obj["mediaType"]?.jsonPrimitive?.content
                ?: "application/vnd.oci.image.layer.v1.tar+gzip"
            val format = layerFormatFromMediaType(mediaType)
                ?: throw EngineException(
                    EngineErrorCode.MANIFEST_UNSUPPORTED,
                    "Layer mediaType not supported: $mediaType (estargz/vendor extensions are rejected)",
                )
            Layer(
                digest = obj["digest"]?.jsonPrimitive?.content ?: throw manifestError(ref, "layer missing digest"),
                size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                mediaType = mediaType,
                format = format,
            )
        }

        ResolvedImage(
            repo = ref.apiRepo,
            manifestDigest = manifestDigest,
            configDigest = config["digest"]?.jsonPrimitive?.content ?: throw manifestError(ref, "config missing digest"),
            layers = layers,
            resolvedHost = host,
        )
    }

    suspend fun downloadBlob(
        ref: ImageRef,
        digest: String,
        dest: File,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        val url = "$scheme://$host/v2/${ref.apiRepo}/blobs/$digest"
        var response = executeOrThrow(httpClient.newCall(buildRequest(url)))
        if (response.code == 401) {
            // 大镜像多层数据中途 token 失效：换 token 重试一次（与 fetchManifest 同策略）
            val authHeader = response.header("WWW-Authenticate")
            response.close()
            cachedToken = obtainTokenWithRetry(authHeader, ref)
            response = executeOrThrow(
                httpClient.newCall(buildRequestBuilder(url).header("Authorization", "Bearer $cachedToken").build()),
            )
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Blob $digest on $host -> HTTP ${resp.code}")
            }
            val body = resp.body ?: throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Blob $digest: empty body")
            val total = body.contentLength()
            val tmp = File(dest.parentFile, dest.name + ".part")
            var done = 0L
            var lastReported = 0L
            // 边写边算摘要（审查 P1-1）：落盘前校验 content digest，防降级/损坏层进入 rootfs
            val md = java.security.MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - lastReported >= 256 * 1024) {
                            onProgress(done, total)
                            lastReported = done
                        }
                    }
                    onProgress(done, total)
                }
            }
            verifyDigest(tmp, digest, md)
            if (tmp.exists() && !tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    throw EngineException(EngineErrorCode.EXTRACT_FAILED, "Cannot finalize blob file: ${dest.path}")
                }
            }
        }
    }

    // ---- internals ----

    /**
     * 校验下载产物 digest（仅支持 sha256，其他算法跳过——manifest 保证 OCI 规范）。
     * [md] 为下载过程中逐块更新的摘要器：直接取其结果即可，**不再重读整个 blob**
     * （审查 C-2：layer 可达数百 MB，二次全量读盘是纯浪费 IO）。
     */
    private fun verifyDigest(file: File, digest: String, md: java.security.MessageDigest) {
        val expected = digest.lowercase()
        if (!expected.startsWith("sha256:")) return
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected.removePrefix("sha256:")) {
            file.delete()
            throw EngineException(
                EngineErrorCode.EXTRACT_FAILED,
                "content digest mismatch: expected $digest, got sha256:$actual",
            )
        }
    }

    private fun buildRequest(url: String): Request =
        buildRequestBuilder(url).build()

    private fun buildRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
            .header(
                "Accept",
                listOf(MANIFEST_LIST_OCI, MANIFEST_LIST_DOCKER, MANIFEST_OCI, MANIFEST_DOCKER).joinToString(", "),
            )
        cachedToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun fetchManifest(ref: ImageRef, reference: String): String {
        val url = "$scheme://$host/v2/${ref.apiRepo}/manifests/$reference"
        var resp = executeOrThrow(httpClient.newCall(buildRequest(url)))
        if (resp.code == 401) {
            val authHeader = resp.header("WWW-Authenticate")
            resp.close()
            cachedToken = obtainTokenWithRetry(authHeader, ref)
            resp = executeOrThrow(
                httpClient.newCall(buildRequestBuilder(url).header("Authorization", "Bearer $cachedToken").build()),
            )
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                throw EngineException(
                    EngineErrorCode.MIRROR_UNAVAILABLE,
                    "Manifest $reference on $host -> HTTP ${r.code}",
                )
            }
            return r.body?.string() ?: throw manifestError(ref, "empty manifest body")
        }
    }

    /** token 请求带退避重试：部分镜像源（如 1ms.run）的 token 端点会间歇性 404/限流。 */
    private suspend fun obtainTokenWithRetry(wwwAuthenticate: String?, ref: ImageRef): String {
        var lastError: EngineException? = null
        repeat(TOKEN_ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(TOKEN_RETRY_DELAY_MS * attempt)
            try {
                return obtainToken(wwwAuthenticate, ref)
            } catch (e: EngineException) {
                lastError = e
            }
        }
        throw lastError!!
    }

    /**
     * 按 WWW-Authenticate 换匿名 token。
     * realm 改写：镜像源返回的 realm 若指向上游（如 auth.docker.io），
     * 保留路径、把主机与协议改写为当前镜像源（方案 §3.2）。
     */
    private fun obtainToken(wwwAuthenticate: String?, ref: ImageRef): String {
        if (wwwAuthenticate.isNullOrBlank() || !wwwAuthenticate.startsWith("Bearer")) {
            throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Registry $host requires auth but no challenge provided")
        }
        val params = wwwAuthenticate.substringAfter("Bearer").split(',')
            .mapNotNull {
                val kv = it.trim().split('=', limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim().trim('"') else null
            }.toMap()
        val realm = params["realm"]
            ?: throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Bearer challenge missing realm")
        val service = params["service"] ?: ""
        val rewritten = realm.toHttpUrl().newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()
        val tokenUrl = rewritten.newBuilder()
            .addQueryParameter("service", service)
            .addQueryParameter("scope", "repository:${ref.apiRepo}:pull")
            .build()
        val resp = executeOrThrow(httpClient.newCall(Request.Builder().url(tokenUrl).build()))
        resp.use { r ->
            if (!r.isSuccessful) {
                throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Token request -> HTTP ${r.code}")
            }
            val body = r.body?.string() ?: throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Token: empty body")
            val json = Json.parseToJsonElement(body).jsonObject
            return json["token"]?.jsonPrimitive?.content
                ?: json["access_token"]?.jsonPrimitive?.content
                ?: throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Token response missing token field")
        }
    }

    private fun selectPlatform(root: kotlinx.serialization.json.JsonObject, hostAbi: HostAbi): String {
        val wanted = hostAbi.dockerPlatform.substringAfter('/')
        val manifests = root["manifests"]?.jsonArray ?: throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "Not an index")
        for (el in manifests) {
            val obj = el.jsonObject
            val platform = obj["platform"]?.jsonObject ?: continue
            if (platform["os"]?.jsonPrimitive?.content == "linux" &&
                platform["architecture"]?.jsonPrimitive?.content == wanted
            ) {
                return obj["digest"]?.jsonPrimitive?.content
                    ?: throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "Index entry missing digest")
            }
        }
        throw EngineException(
            EngineErrorCode.MANIFEST_UNSUPPORTED,
            "No manifest for linux/$wanted (device ABI: ${hostAbi.androidAbi})",
        )
    }

    private fun manifestError(ref: ImageRef, message: String) =
        EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "${ref.host ?: "docker.io"}/${ref.repo}: $message")

    private fun executeOrThrow(call: Call): Response = try {
        call.execute()
    } catch (e: IOException) {
        throw EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "Request ${call.request().url} failed: ${e.message}", e)
    }

    private companion object {
        const val MANIFEST_OCI = "application/vnd.oci.image.manifest.v1+json"
        const val MANIFEST_DOCKER = "application/vnd.docker.distribution.manifest.v2+json"
        const val MANIFEST_LIST_OCI = "application/vnd.oci.image.index.v1+json"
        const val MANIFEST_LIST_DOCKER = "application/vnd.docker.distribution.manifest.list.v2+json"

        /** token 请求重试次数与退避基数（部分镜像源 token 端点间歇性 404/限流，窗口较长）。 */
        const val TOKEN_ATTEMPTS = 4
        const val TOKEN_RETRY_DELAY_MS = 10_000L
    }
}
