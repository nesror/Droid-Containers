package cn.yzapp.androidcontainer.core.data

import android.content.Context
import android.os.Build
import cn.yzapp.androidcontainer.core.common.DefaultDispatcherProvider
import cn.yzapp.androidcontainer.core.common.detectHostAbi
import cn.yzapp.androidcontainer.core.data.db.ImageEntity
import cn.yzapp.androidcontainer.core.data.db.InventoryDao
import cn.yzapp.androidcontainer.core.engine.oci.ImageRef
import cn.yzapp.androidcontainer.core.engine.oci.MirrorProbe
import cn.yzapp.androidcontainer.core.engine.pull.PullEngine
import cn.yzapp.androidcontainer.core.engine.load.DockerLoadImporter
import cn.yzapp.androidcontainer.core.engine.tar.TarExtractor
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import cn.yzapp.androidcontainer.core.model.PullProgress
import cn.yzapp.androidcontainer.core.model.PullStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 镜像仓库门面：拉取编排 + 库存持久化 + 拉取进度状态（方案 §3.2/§4/§6）。
 * 拉取在调用方协程内执行（dataSync 前台服务的 scope），
 * 进度经 [pullStates] 共享给 UI 与通知。
 */
class ImageRepository(
    context: Context,
    private val dao: InventoryDao,
    /** 镜像源提供者（M5 起从设置读取，可动态变更）。 */
    private val mirrorsProvider: suspend () -> List<String> = { DEFAULT_MIRRORS },
) {

    private val appContext = context.applicationContext
    private val engineDir = File(appContext.filesDir, "engine")

    private val scope = CoroutineScope(SupervisorJob() + DefaultDispatcherProvider().io)
    private val pullMutex = Mutex()

    private val _pullStates = MutableStateFlow<Map<String, PullProgress>>(emptyMap())
    val pullStates: StateFlow<Map<String, PullProgress>> = _pullStates.asStateFlow()

    /** 镜像事件（M9 /events 流）：type = pull / destroy。 */
    data class ImageEvent(val type: String, val ref: String, val timestampMs: Long = System.currentTimeMillis())

    private val _events = MutableSharedFlow<ImageEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ImageEvent> = _events.asSharedFlow()

    val inventory: Flow<List<ImageEntity>> = dao.observeImages()

    /** 拉取镜像：返回库存记录；失败抛 EngineException，并把 FAILED 进度写入 pullStates。 */
    suspend fun pull(refRaw: String, onLog: (String) -> Unit = {}): ImageEntity {
        AppLogger.i(TAG, "pull requested ref=$refRaw")
        val hostAbi = detectHostAbi(Build.SUPPORTED_ABIS.toList())
            ?: throw EngineException(EngineErrorCode.UNSUPPORTED_DEVICE, "No 64-bit ABI supported on this device")

        return pullMutex.withLock {
            val engine = PullEngine(mirrorsProvider(), engineDir, hostAbi, DefaultDispatcherProvider())
            val progressJob = scope.launch {
                engine.progress.collect { progress -> _pullStates.value = _pullStates.value + (refRaw to progress) }
            }
            try {
                val rootfs = engine.pull(refRaw) { msg -> AppLogger.i(TAG, msg) }
                val parsed = ImageRef.parse(refRaw)
                val entity = ImageEntity(
                    ref = refRaw,
                    repo = parsed.apiRepo,
                    tag = parsed.tag,
                    manifestDigest = "",
                    rootfsPath = rootfs.path,
                    sizeBytes = rootfs.walkBottomUp().filter { it.isFile }.map { it.length() }.sum(),
                    createdAt = System.currentTimeMillis(),
                )
                dao.upsertImage(entity)
                _events.tryEmit(ImageEvent("pull", refRaw))
                entity
            } catch (e: EngineException) {
                _pullStates.value = _pullStates.value + (
                    refRaw to PullProgress(
                        imageRef = refRaw,
                        stage = PullStage.FAILED,
                        message = "[${e.code}] ${e.message}",
                    )
                    )
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "pull failed ref=$refRaw", e)
                throw e
            } catch (e: Throwable) {
                // Error（如 UnsatisfiedLinkError）不属于 Exception，若不在此落日志
                // 会沿 runCatching 静默消失，pullStates 冻结在最后阶段（2026-09-18 zstd 坑）
                AppLogger.e(TAG, "pull crashed ref=$refRaw", e)
                _pullStates.value = _pullStates.value + (
                    refRaw to PullProgress(
                        imageRef = refRaw,
                        stage = PullStage.FAILED,
                        message = "${e.javaClass.simpleName}: ${e.message}",
                    )
                    )
                throw e
            } finally {
                progressJob.cancel()
            }
        }
    }

    /**
     * 导入 `docker save` 导出的 tar（SAF 临时文件）：解层出 rootfs 并登记库存。
     * ref 取 manifest 的 RepoTags[0]，无 tag 时回落 `imported:<id>`；
     * 同 ref 重复导入 = 覆盖（rootfs 先清后解）。不需要网络。
     */
    suspend fun importTar(tarFile: File, onLog: (String) -> Unit = {}): ImageEntity =
        withContext(DefaultDispatcherProvider().io) {
            AppLogger.i(TAG, "import tar file=${tarFile.name} size=${tarFile.length()}")
            // 与 pull/remove 共用同一把锁（审查 P1-15）：导入会先清后解 layers/<ref> 目录，
            // 并发 pull/remove 会形成"边写边删"
            pullMutex.withLock {
                val loaded = DockerLoadImporter(engineDir).load(tarFile, onLog)
                val parsed = runCatching { ImageRef.parse(loaded.ref) }.getOrNull()
                val entity = ImageEntity(
                    ref = loaded.ref,
                    repo = parsed?.apiRepo ?: loaded.ref.substringBeforeLast(':'),
                    tag = parsed?.tag ?: loaded.ref.substringAfterLast(':', "latest"),
                    manifestDigest = "",
                    rootfsPath = loaded.rootfs.path,
                    sizeBytes = loaded.rootfs.walkBottomUp().filter { it.isFile }.map { it.length() }.sum(),
                    createdAt = System.currentTimeMillis(),
                )
                dao.upsertImage(entity)
                _events.tryEmit(ImageEvent("pull", loaded.ref))
                AppLogger.i(TAG, "import done ref=${loaded.ref}")
                entity
            }
        }

    /** 镜像源测速（设置页「测速并排序」）：/v2/ 端点 RTT，不可达为 null。 */
    suspend fun probeMirrors(hosts: List<String>): List<MirrorProbe.Result> =
        MirrorProbe.probeAll(hosts)

    /** 删除镜像：rootfs 目录与库存记录（防符号链接的安全删除）。 */
    suspend fun remove(ref: String) = withContext(DefaultDispatcherProvider().io) {
        // 与 pull/importTar 共用同一把锁（审查 P1-15）：remove 会删 layers/<ref> 整目录，
        // 与并发 pull 的解压写入构成"边写边删"
        pullMutex.withLock {
            dao.findImage(ref)?.let { entity ->
                val rootfs = File(entity.rootfsPath)
                rootfs.parentFile?.let { parent ->
                    if (parent.exists()) {
                        // 删除整个 layers/<image> 目录（含 rootfs），逐项安全删除
                        TarExtractor().deleteEntry(parent)
                    }
                }
            }
            dao.deleteImage(ref)
            _pullStates.value = _pullStates.value - ref
            _events.tryEmit(ImageEvent("destroy", ref))
        }
    }

    /**
     * 启动时清理「无库存记录」的 layers/<image> 目录（审查 P1-14 兜底）。
     * 库存表一旦被清（如历史破坏性迁移），rootfs 目录（百 MB 级）会成为 UI 无法
     * 清理的孤儿——以「库存 rootfsPath 的父目录」白名单判定归属，不在名单即删。
     */
    suspend fun cleanupOrphanLayers() = withContext(DefaultDispatcherProvider().io) {
        val layersDir = File(engineDir, "layers")
        if (!layersDir.isDirectory) return@withContext
        val known = dao.observeImages().first()
            .mapNotNull { File(it.rootfsPath).parentFile?.canonicalPath }
            .toSet()
        layersDir.listFiles()?.forEach { dir ->
            if (dir.canonicalPath !in known) {
                TarExtractor().deleteEntry(dir)
                AppLogger.w(TAG, "orphan layers dir removed: ${dir.name}")
            }
        }
    }

    companion object {
        private const val TAG = "Image"

        /** 默认镜像源（可被设置项覆盖，M5）。大陆可达源优先，官方源兜底。 */
        val DEFAULT_MIRRORS = listOf(
            "docker.1ms.run",
            "docker.m.daocloud.io",
            "dockerproxy.net",
            "registry-1.docker.io",
        )
    }
}
