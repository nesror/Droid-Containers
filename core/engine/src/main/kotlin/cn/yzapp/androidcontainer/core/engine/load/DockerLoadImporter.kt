package cn.yzapp.androidcontainer.core.engine.load

import cn.yzapp.androidcontainer.core.common.DefaultDispatcherProvider
import cn.yzapp.androidcontainer.core.common.DispatcherProvider
import cn.yzapp.androidcontainer.core.engine.oci.ImageRef
import cn.yzapp.androidcontainer.core.engine.tar.LayerFormat
import cn.yzapp.androidcontainer.core.engine.tar.TarExtractor
import cn.yzapp.androidcontainer.core.engine.tar.TarReader
import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * `docker load` 等价物：把 `docker save` 导出的 tar 导入为本地镜像库存。
 *
 * 支持两种容器格式（内层由 manifest.json 统一索引，无需区分）：
 * - legacy 布局：`manifest.json` + `<imageId>/layer.tar` + `<imageId>/json`；
 * - OCI layout（docker 25+）：`index.json` + `blobs/sha256/<digest>`，manifest.json 仍会写出；
 * - 整档 gzip（`docker save | gzip`）：文件头魔数 `1f 8b` 自动解包。
 *
 * 实现要点：manifest.json 可能出现在层条目**之后**（顺序无保证），而层路径要靠它索引，
 * 所以对本地临时文件做两次顺序扫描——第一遍只读 manifest.json，第二遍解压层与 config。
 * 层压缩格式按字节魔数嗅探（docker save 的层多为纯 tar，OCI blob 可能为 gzip/zstd）。
 *
 * 产物与 PullEngine 完全一致：`layers/<...>/rootfs` 目录 + 同级 `config`
 * （镜像 ENTRYPOINT/CMD blob），可被 start()/openTerminal() 直接消费。
 */
class DockerLoadImporter(
    private val engineDir: File,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
) {

    data class LoadedImage(val ref: String, val rootfs: File)

    /** 单条 manifest.json 记录（只需这三个字段）。 */
    private data class SaveManifest(val config: String, val repoTags: List<String>, val layers: List<String>)

    suspend fun load(tarFile: File, onLog: (String) -> Unit = {}): LoadedImage = withContext(dispatchers.io) {
        val manifest = readManifest(tarFile)
        val ref = manifest.repoTags.firstOrNull().orEmpty().ifEmpty { "imported:${importedStem(manifest.config)}" }
        onLog("manifest ok: ref=$ref layers=${manifest.layers.size}")

        val extractor = TarExtractor()
        val rootfs = rootfsDirFor(ref)
        if (rootfs.exists()) extractor.deleteEntry(rootfs)
        rootfs.mkdirs()

        // Pass 2：按 manifest 索引解压层 + 摘出 config blob
        val configTarget = File(rootfs.parentFile, "config")
        val wanted = (manifest.layers + manifest.config).map { it.normalizeEntryName() }.toSet()
        val seen = mutableSetOf<String>()
        var layerIndex = 0
        openArchive(tarFile).use { input ->
            val reader = TarReader(input)
            while (true) {
                val entry = reader.nextEntry() ?: break
                val name = entry.name.normalizeEntryName()
                if (name !in wanted || name in seen || !entry.isRegularFile) {
                    // 未命中条目也必须消费数据体 + 填充，否则流错位（oci-layout、目录项等）
                    if (!entry.isDirectory) reader.skipEntryData(entry)
                    continue
                }
                seen += name

                if (name == manifest.config.normalizeEntryName()) {
                    reader.entryDataStream(entry).use { configTarget.outputStream().use { out -> it.copyTo(out) } }
                    reader.skipPaddingAfter(entry)
                    onLog("config blob saved")
                } else {
                    layerIndex++
                    onLog("extracting layer $layerIndex/${manifest.layers.size}: $name")
                    val format = sniffLayerFormat(reader.entryDataStream(entry))
                    format.second.use { stream ->
                        extractor.extract(stream, format.first, rootfs)
                    }
                    // 层条目在外层 tar 里的 512 对齐填充：消费完再继续，否则后续条目错位
                    reader.skipPaddingAfter(entry)
                }
            }
        }
        if (layerIndex < manifest.layers.size) {
            throw EngineException(
                EngineErrorCode.MANIFEST_UNSUPPORTED,
                "docker save archive is incomplete: found $layerIndex/${manifest.layers.size} layers",
            )
        }

        onLog("ready: ${rootfs.path}")
        LoadedImage(ref = ref, rootfs = rootfs)
    }

    // ------------------------------------------------------------ 内部实现

    /** Pass 1：只读 manifest.json（取第一条记录；docker save 单导出通常只有一条）。 */
    private fun readManifest(tarFile: File): SaveManifest {
        openArchive(tarFile).use { input ->
            val reader = TarReader(input)
            while (true) {
                val entry = reader.nextEntry() ?: break
                if (entry.name.normalizeEntryName() == MANIFEST_NAME && entry.isRegularFile) {
                    val text = reader.entryDataStream(entry).buffered().use { it.readBytes().toString(Charsets.UTF_8) }
                    return parseManifest(text)
                }
                reader.skipEntryData(entry)
            }
        }
        throw EngineException(
            EngineErrorCode.MANIFEST_UNSUPPORTED,
            "not a docker save archive: $MANIFEST_NAME not found in ${tarFile.name}",
        )
    }

    private fun parseManifest(text: String): SaveManifest = try {
        val first = Json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject
            ?: throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "manifest.json is empty")
        SaveManifest(
            config = first["Config"]?.jsonPrimitive?.content
                ?: throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "manifest.json entry has no Config"),
            repoTags = first["RepoTags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            layers = first["Layers"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "manifest.json entry has no Layers"),
        )
    } catch (e: EngineException) {
        throw e
    } catch (e: Exception) {
        throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "manifest.json parse failed: ${e.message}", e)
    }

    /** 整档 gzip 兜底：按文件头魔数选择流。 */
    private fun openArchive(tarFile: File): InputStream {
        val raw = tarFile.inputStream().buffered(1024 * 1024)
        val head = ByteArray(2)
        raw.mark(2)
        val read = raw.read(head, 0, 2)
        raw.reset()
        return if (read == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
            GZIPInputStream(raw)
        } else {
            raw
        }
    }

    /**
     * 层压缩格式按魔数嗅探：gzip(1f 8b) / zstd(28 b5 2f fd) / 纯 tar。
     * mark/reset 已把嗅探字节回退，返回的流可从头消费；调用方负责关闭。
     */
    private fun sniffLayerFormat(stream: InputStream): Pair<LayerFormat, InputStream> {
        val buffered = stream.buffered(1024 * 1024)
        buffered.mark(4)
        val head = ByteArray(4)
        val read = try {
            var off = 0
            while (off < 4) {
                val n = buffered.read(head, off, 4 - off)
                if (n == -1) break
                off += n
            }
            off
        } catch (_: IOException) {
            0
        }
        buffered.reset()
        val format = when {
            read >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> LayerFormat.GZIP
            read >= 4 && head[0] == 0x28.toByte() && head[1] == 0xb5.toByte() &&
                head[2] == 0x2f.toByte() && head[3] == 0xfd.toByte() -> LayerFormat.ZSTD
            else -> LayerFormat.TAR
        }
        return format to buffered
    }

    private fun rootfsDirFor(refRaw: String): File {
        val parsed = runCatching { ImageRef.parse(refRaw) }.getOrNull()
        val safe = if (parsed != null) {
            (parsed.host ?: "docker.io") + "_" + parsed.repo.replace('/', '_') + "_" + parsed.tag
        } else {
            "imported_" + refRaw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
        return File(engineDir, "layers/$safe/rootfs")
    }

    /** docker save 条目名可能带 `./` 前缀；manifest 里的路径也可能两种写法混用。 */
    private fun String.normalizeEntryName(): String = removePrefix("./").trimEnd('/')

    /**
     * 无 RepoTags 时的回退名来源：legacy 布局 Config 是 `<id>/json` 取 id 段，
     * OCI 布局是 `blobs/sha256/<digest>` 取 digest 段，再剥掉扩展名。
     */
    private fun importedStem(configPath: String): String {
        val base = configPath.substringAfterLast('/')
        val stem = if (base == "json") configPath.substringBeforeLast('/').substringAfterLast('/') else base
        return stem.substringBeforeLast('.').ifEmpty { "image" }
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
    }
}
