package cn.yzapp.androidcontainer.core.engine.pull

import cn.yzapp.androidcontainer.core.common.DispatcherProvider
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.HostAbi
import cn.yzapp.androidcontainer.core.model.PullProgress
import cn.yzapp.androidcontainer.core.model.PullStage
import cn.yzapp.androidcontainer.core.engine.oci.ImageRef
import cn.yzapp.androidcontainer.core.engine.oci.OciRegistryClient
import cn.yzapp.androidcontainer.core.engine.tar.TarExtractor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * 拉取编排（方案 §3.2/§3.3 + §6 状态机）：
 *
 * mirrors 逐源回退（前一源任何可重试失败 → 下一源），单源内部：
 * resolve（401→token、realm 改写、按 ABI 选平台）→ 逐层下载（digest 缓存命中跳过）
 * → 逐层解压到 rootfs → READY。
 *
 * 进度经 SharedFlow<PullProgress> 暴露；字节级回调 400ms 节流，
 * 阶段切换立即推送（方案 §3.3）。取消 = 取消调用方协程（层间检查点）。
 */
class PullEngine(
    private val mirrors: List<String>,
    private val engineDir: File,
    private val hostAbi: HostAbi,
    private val dispatchers: DispatcherProvider,
    httpClient: OkHttpClient = OkHttpClient.Builder().build(),
    /** 默认 https；测试环境注入 http 以便本地模拟 registry。 */
    private val scheme: String = "https",
) {

    private val extractor = TarExtractor()
    private val blobCacheDir = File(engineDir, "cache/blobs")

    private val _progress = MutableSharedFlow<PullProgress>(replay = 1, extraBufferCapacity = 64)
    val progress: SharedFlow<PullProgress> = _progress

    @Volatile
    private var lastEmitAt = 0L

    /**
     * 拉取镜像并解压出 rootfs。成功返回 rootfs 目录；抛出最后一个源的 EngineException。
     */
    suspend fun pull(imageRefRaw: String, onLog: (String) -> Unit = {}): File {
        val ref = ImageRef.parse(imageRefRaw)
        val rootfs = rootfsDirFor(ref)
        var lastError: EngineException? = null

        for (mirror in mirrors) {
            try {
                emitProgress(ref, PullStage.RESOLVING_MANIFEST, mirrorHost = mirror)
                onLog("mirror=$mirror resolving ${ref.apiRepo}:${ref.tag}")
                ensureActiveCheckpoint()

                val client = OciRegistryClient(mirror, httpClient = okHttpFor(mirror), scheme = scheme)
                val resolved = client.resolve(ref, hostAbi)

                // 新解压前先清掉旧 rootfs（同 tag 重拉）
                if (rootfs.exists()) extractor.deleteEntry(rootfs)
                rootfs.mkdirs()

                var layerIndex = 0
                var completedLayers = 0
                var completedBytes = 0L
                val totalAllBytes = resolved.layers.sumOf { it.size }
                for (layer in resolved.layers) {
                    layerIndex++
                    ensureActiveCheckpoint()
                    val blob = File(blobCacheDir, layer.digest.replace(':', '_'))
                    if (blob.isFile && blob.length() == layer.size) {
                        onLog("cache hit: ${layer.digest.take(19)}")
                    } else {
                        emitProgress(
                            ref, PullStage.DOWNLOADING, mirror,
                            currentLayer = layerIndex, totalLayers = resolved.layers.size,
                            completedLayers = completedLayers,
                            downloadedBytes = 0, totalBytes = layer.size,
                            overallDownloadedBytes = completedBytes,
                            overallTotalBytes = totalAllBytes,
                        )
                        blobCacheDir.mkdirs()
                        client.downloadBlob(ref, layer.digest, blob) { done, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastEmitAt >= THROTTLE_MS) {
                                lastEmitAt = now
                                // 回调非挂起环境：tryEmit + extraBufferCapacity 足够
                                _progress.tryEmit(
                                    PullProgress(
                                        imageRef = refString(ref),
                                        stage = PullStage.DOWNLOADING,
                                        currentLayer = layerIndex,
                                        totalLayers = resolved.layers.size,
                                        completedLayers = completedLayers,
                                        downloadedBytes = done,
                                        totalBytes = if (total > 0) total else layer.size,
                                        overallDownloadedBytes = completedBytes + done,
                                        overallTotalBytes = totalAllBytes,
                                        mirrorHost = mirror,
                                    ),
                                )
                            }
                        }
                    }
                    completedLayers++
                    completedBytes += layer.size

                    emitProgress(
                        ref, PullStage.EXTRACTING, mirror,
                        currentLayer = layerIndex, totalLayers = resolved.layers.size,
                        completedLayers = completedLayers,
                        overallDownloadedBytes = completedBytes,
                        overallTotalBytes = totalAllBytes,
                    )
                    ensureActiveCheckpoint()
                    blob.inputStream().buffered(1024 * 1024).use { input ->
                        extractor.extract(input, layer.format, rootfs)
                    }
                }

                // 镜像 config blob（ENTRYPOINT/CMD）：落盘到 rootfs 同级 config，
                // 供启动时回落镜像默认入口（方案 §3.4；旧镜像无此文件 → 继续走 shell 回落）
                val configBlob = File(blobCacheDir, resolved.configDigest.replace(':', '_'))
                if (!(configBlob.isFile && configBlob.length() > 0L)) {
                    blobCacheDir.mkdirs()
                    client.downloadBlob(ref, resolved.configDigest, configBlob)
                }
                configBlob.copyTo(File(rootfs.parentFile, "config"), overwrite = true)

                emitProgress(ref, PullStage.READY, mirror, message = rootfs.path)
                onLog("ready: ${rootfs.path}")
                return rootfs
            } catch (e: EngineException) {
                lastError = e
                onLog("mirror=$mirror failed: [${e.code}] ${e.message}")
                if (!e.code.retryable) throw e
                // 可重试 → 下一镜像源
            } catch (e: java.io.IOException) {
                lastError = EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "mirror=$mirror: ${e.message}", e)
                onLog("mirror=$mirror io failure: ${e.message}")
            }
        }
        throw lastError ?: EngineException(EngineErrorCode.MIRROR_UNAVAILABLE, "no mirrors configured")
    }

    internal fun rootfsDirFor(ref: ImageRef): File {
        val safe = (ref.host ?: "docker.io") + "_" + ref.repo.replace('/', '_') + "_" + ref.tag
        return File(engineDir, "layers/$safe/rootfs")
    }

    private suspend fun emitProgress(
        ref: ImageRef,
        stage: PullStage,
        mirrorHost: String? = null,
        currentLayer: Int = 0,
        totalLayers: Int = 0,
        completedLayers: Int = 0,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
        overallDownloadedBytes: Long = 0,
        overallTotalBytes: Long = 0,
        message: String? = null,
    ) {
        _progress.emit(
            PullProgress(
                imageRef = refString(ref),
                stage = stage,
                currentLayer = currentLayer,
                totalLayers = totalLayers,
                completedLayers = completedLayers,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                overallDownloadedBytes = overallDownloadedBytes,
                overallTotalBytes = overallTotalBytes,
                mirrorHost = mirrorHost,
                message = message,
            ),
        )
    }

    private fun refString(ref: ImageRef): String = "${ref.host ?: "docker.io"}/${ref.repo}:${ref.tag}"

    private suspend fun ensureActiveCheckpoint() {
        // 协作式取消检查点：层与阶段之间
        currentCoroutineContext().ensureActive()
    }

    private fun okHttpFor(mirror: String): OkHttpClient = OkHttpClient.Builder().build()

    private companion object {
        const val THROTTLE_MS = 400L
    }
}
