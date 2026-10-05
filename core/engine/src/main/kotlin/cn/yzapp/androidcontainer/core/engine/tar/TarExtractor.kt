package cn.yzapp.androidcontainer.core.engine.tar

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import com.github.luben.zstd.ZstdInputStream

data class ExtractResult(
    val entryCount: Int,
    val totalBytes: Long,
    /** 因权限等问题降级为警告而跳过的项（方案 §3.3-3，对齐 android-docker-cli 行为）。 */
    val warnings: List<String>,
)

/**
 * OCI 层 tar 解压器（rootfs 构建，方案 §3.3）。
 *
 * Android 特有坑全部在此处理：
 * 1. zstd 用 zstd-jni 的 @aar 制品（Bionic 原生库随包分发，System.loadLibrary 加载）；
 *    纯 Java 实现（aircompressor）依赖 OpenJDK 专属的 Unsafe 字段，Android 不可用，
 *    且存在大窗口层解压缺陷，勿换（对齐 home_assistant_flutter 已验证组合）；
 * 2. 同名【文件】条目覆盖前必须先 deleteEntry——直接 FileOutputStream 会跟进符号链接，
 *    Alpine 的 usr/bin/nslookup → /bin/busybox 这类链接在 Android 上指向只读 /system/bin，得到 EROFS；
 *    目录条目例外：只确保存在、绝不清空（见 extractDirectory，OCI 删除语义仅由 whiteout 承担）；
 * 3. 目录删除禁用 File.deleteRecursively()（同样跟进链接），逐项安全删除；
 * 4. whiteout：.wh.<name> 删除同名项；.wh..wh..opq 清空父目录；删不掉降级为警告继续；
 * 5. tar 头 mode 含执行位（0111）时为 owner 补执行位——只补不收敛；
 * 6. 拒绝绝对路径与 ".." 逃逸（防恶意镜像写入 rootfs 外）；
 * 7. 父级符号链接逃逸（CWE-59）同样拒绝：safeResolve 只校验条目名字符串，
 *    若中间某级目录是先前条目创建的符号链接（如 `esc` → 宿主任意路径，随后
 *    条目 `esc/db/x`），文件写入 / 递归删除 / 链接创建都会**跟进**该链接越出
 *    rootfs——所有落盘与删除前必须确认父链解析后仍在 rootfs 内。
 */
class TarExtractor {

    fun extract(
        layer: InputStream,
        format: LayerFormat,
        destination: File,
        onProgress: (bytesExtracted: Long) -> Unit = {},
    ): ExtractResult {
        val decompressed = when (format) {
            LayerFormat.TAR -> layer
            LayerFormat.GZIP -> GZIPInputStream(layer, 64 * 1024)
            // zstd-jni @aar：Bionic 原生库随包分发，勿换回默认 JAR 或纯 Java 实现
            // （详见类注释与 build.gradle.kts 依赖处说明）
            LayerFormat.ZSTD -> ZstdInputStream(layer)
        }
        val warnings = mutableListOf<String>()
        var entryCount = 0
        var totalBytes = 0L

        BufferedInputStream(decompressed, 1024 * 1024).use { input ->
            val reader = TarReader(input)
            while (true) {
                val entry = reader.nextEntry() ?: break
                entryCount++
                when {
                    isWhiteout(entry.name) -> handleWhiteout(reader, entry, destination, warnings)
                    entry.isDirectory -> extractDirectory(entry, destination, warnings)
                    entry.isSymlink -> extractSymlink(entry, destination, warnings)
                    entry.isHardlink -> extractHardlink(entry, destination, warnings)
                    entry.isRegularFile -> {
                        extractRegularFile(reader, entry, destination, warnings)
                        totalBytes += entry.size
                    }
                    else -> reader.skipEntryData(entry) // device/fifo 等不支持项跳过
                }
                onProgress(totalBytes)
            }
        }
        return ExtractResult(entryCount, totalBytes, warnings)
    }

    // ---- whiteout ----

    private fun isWhiteout(name: String): Boolean =
        name.substringAfterLast('/') == WHITEOUT_OPAQUE || name.substringAfterLast('/').startsWith(WHITEOUT_PREFIX)

    private fun handleWhiteout(
        reader: TarReader,
        entry: TarEntry,
        destination: File,
        warnings: MutableList<String>,
    ) {
        // 数据体必须消费完，保证流位置正确
        reader.skipEntryData(entry)
        val fileName = entry.name.substringAfterLast('/')
        if (fileName == WHITEOUT_OPAQUE) {
            val parentPath = entry.name.substringBeforeLast('/', "")
            val parent = if (parentPath.isEmpty()) destination else safeResolve(destination, parentPath)
            if (parent != null && parent.isDirectory) {
                // 清空前确认父链没有穿过符号链接，否则 listFiles/递归删除作用于 rootfs 之外
                requireParentsInsideRootfs(destination, parent, entry.name)
                parent.listFiles()?.forEach { child ->
                    if (!deleteEntry(child)) {
                        warnings += "opaque whiteout: cannot delete ${child.path}"
                    }
                }
            }
        } else {
            val targetName = fileName.removePrefix(WHITEOUT_PREFIX)
            if (targetName.isEmpty() || targetName == "." || targetName == ".." || '/' in targetName) {
                // ".wh.." 之类名字解析出 ".." 会让 File(parent, target) 指到 parent 之外
                warnings += "whiteout: unsafe target name in ${entry.name}"
                return
            }
            val parentPath = entry.name.substringBeforeLast('/', "")
            val parent = if (parentPath.isEmpty()) destination else safeResolve(destination, parentPath)
            if (parent != null) {
                requireParentsInsideRootfs(destination, parent, entry.name)
                val target = File(parent, targetName)
                if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                    if (!deleteEntry(target)) {
                        warnings += "whiteout: cannot delete ${target.path}"
                    }
                }
            }
        }
    }

    // ---- entries ----

    private fun extractDirectory(entry: TarEntry, destination: File, warnings: MutableList<String>) {
        val target = safeResolve(destination, entry.name) ?: return
        requireParentsInsideRootfs(destination, target, entry.name)
        // OCI 语义：目录条目只表示“确保该目录存在”，绝不能递归清空已有内容——
        // apk 安装层的 tar 会重复出现 lib/、usr/bin/ 等父目录条目，若先删除会把
        // 前一层已解压的文件（如 musl loader）整树抹掉（2026-09-18 真机根因，
        // eclipse-mosquitto:2.0 的 ld-musl 即此丢失）。删除只能由 whiteout 触发。
        // 仅当同名路径被符号链接或普通文件占据时，才需清理后再建目录。
        if (Files.isSymbolicLink(target.toPath()) || (target.exists() && !target.isDirectory)) {
            if (!deleteEntry(target)) {
                warnings += "cannot clear path for directory ${target.path}"
                return
            }
        }
        if (!target.exists() && !target.mkdirs()) {
            warnings += "cannot create directory ${target.path}"
            return
        }
        if ((entry.mode and 0b000_001_001L) != 0L) {
            target.setExecutable(true, true)
        }
        target.setReadable(true, true)
    }

    private fun extractSymlink(entry: TarEntry, destination: File, warnings: MutableList<String>) {
        val target = safeResolve(destination, entry.name) ?: return
        requireParentsInsideRootfs(destination, target, entry.name)
        deleteEntry(target)
        target.parentFile?.mkdirs()
        try {
            // 链接目标保持镜像原值（docker 语义）：proot 把 rootfs 绑为 /，容器内
            // execve 时会对绝对链接目标按容器路径重新解析（2026-09-30 真机回归根因：
            // 曾改写成宿主绝对路径 rootfs/bin/busybox，proot 去 rootfs/data/... 找，
            // /bin/sh 恒 ENOENT，所有 Alpine 系镜像起不来）。宿主侧绝对目标要么
            // 悬空、要么指回 rootfs 内相对路径可解析处，无越外面；越界防护由
            // requireParentsInsideRootfs（父链）与 deleteEntry（不跟进链接）承担。
            Files.createSymbolicLink(target.toPath(), File(entry.linkName).toPath())
        } catch (e: IOException) {
            // 部分文件系统不允许创建符号链接：降级为警告，不中断整层解压
            warnings += "symlink ${entry.name} -> ${entry.linkName}: ${e.message}"
        } catch (e: SecurityException) {
            warnings += "symlink ${entry.name} -> ${entry.linkName}: ${e.message}"
        }
    }

    private fun extractHardlink(entry: TarEntry, destination: File, warnings: MutableList<String>) {
        val target = safeResolve(destination, entry.name) ?: return
        requireParentsInsideRootfs(destination, target, entry.name)
        val source = safeResolve(destination, entry.linkName) ?: return
        requireParentsInsideRootfs(destination, source, entry.name)
        deleteEntry(target)
        target.parentFile?.mkdirs()
        try {
            Files.createLink(target.toPath(), source.toPath())
            return
        } catch (_: IOException) {
            // Android 文件系统不支持硬链接——部分镜像的 tar 用 hardlink 条目表示
            // 同 inode 文件，直接跳过会导致目标缺失、execve ENOENT，降级为符号链接或拷贝
        } catch (_: SecurityException) {
            // 同上，降级处理
        }
        // 降级 1：相对路径符号链接（目标此时可以尚不存在，运行时再解析）
        try {
            val link = target.toPath()
            val relative = link.parent?.relativize(source.toPath()) ?: source.toPath()
            Files.createSymbolicLink(link, relative)
            return
        } catch (_: IOException) {
            // 部分文件系统不允许符号链接，继续降级
        } catch (_: SecurityException) {
            // 继续降级
        }
        // 降级 2：源文件已存在时整份拷贝
        if (source.isFile) {
            try {
                Files.copy(source.toPath(), target.toPath())
                return
            } catch (_: IOException) {
                // 落入警告
            } catch (_: SecurityException) {
                // 落入警告
            }
        }
        warnings += "hardlink ${entry.name}: link/symlink/copy all failed (source=${entry.linkName})"
    }

    private fun extractRegularFile(
        reader: TarReader,
        entry: TarEntry,
        destination: File,
        warnings: MutableList<String>,
    ) {
        val target = safeResolve(destination, entry.name) ?: run {
            reader.skipEntryData(entry)
            return
        }
        // 父链若穿过符号链接，FileOutputStream 会跟进链接把文件写到 rootfs 之外
        requireParentsInsideRootfs(destination, target, entry.name)
        // 关键：先删除同名条目（只删链接本身），绝不跟进符号链接
        deleteEntry(target)
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                warnings += "cannot create parent for ${target.path}"
                reader.skipEntryData(entry)
                return
            }
        }
        try {
            FileOutputStream(target).use { out ->
                reader.entryDataStream(entry).use { data ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = data.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                }
            }
            // 数据体后是对齐填充，必须跳过，否则下一个 tar 头解析错位
            reader.skipPaddingAfter(entry)
        } catch (e: IOException) {
            throw EngineException(EngineErrorCode.EXTRACT_FAILED, "Failed to extract ${entry.name}: ${e.message}", e)
        }
        if (entry.hasAnyExecuteBit) {
            target.setExecutable(true, true)
        }
    }

    // ---- path safety ----

    /**
     * 解析条目名到 rootfs 内的目标文件；拒绝绝对路径与 ".." 逃逸（方案 §3.3-6）。
     * 返回 null 表示条目名指向 rootfs 根本身（如 "etc/"），无需落盘。
     */
    internal fun safeResolve(destination: File, name: String): File? {
        val normalized = name.trimStart('/')
        if (name.startsWith('/')) {
            // 绝对路径一律拒绝（GNU tar 的 docker 层均为相对路径）
            if (normalized.isEmpty()) return null
            throw EngineException(EngineErrorCode.EXTRACT_FAILED, "Absolute path in layer: $name")
        }
        if (normalized.isEmpty()) return null
        val parts = normalized.split('/')
        for (part in parts) {
            if (part == "..") {
                throw EngineException(EngineErrorCode.EXTRACT_FAILED, "Unsafe path in layer: $name")
            }
        }
        return File(destination, normalized)
    }

    /**
     * 校验 [target] 的父链没有穿过指向 rootfs 之外的符号链接（CWE-59）。
     *
     * 实现要点（审查 C-3 修订）：
     * - 用 `Files.isSymbolicLink`（lstat，NOFOLLOW）逐级探测，**正常情况下零 realpath**
     *   ——原实现对每一级父目录都调 `canonicalFile`，把 syscall 放大到 O(深度 × 条目数)，
     *   五万条目级的镜像层会因此多出数十万次路径解析；
     * - 一旦发现符号链接，用 `toRealPath()` 严格解析（会递归解析多级链接），落点不在
     *   rootfs 内即拒绝。这里**不能用 `canonicalFile`**：它对悬空链接会退化为「不解析
     *   链接的原路径」，看起来仍在 rootfs 内，而后续 `mkdirs()` / `FileOutputStream`
     *   会跟进该链接把内容写到 rootfs 之外；
     * - 悬空链接、无法解析的链接一律按逃逸处理（上层整层失败，与 ".." 拒绝同级）；
     * - 只查父链、不查 [target] 自身——target 位置的旧符号链接在落盘前由
     *   [deleteEntry] 删除链接本身，不会被跟进。
     */
    private fun requireParentsInsideRootfs(destination: File, target: File, entryName: String) {
        val rootPath = destination.canonicalFile.path
        var dir: File? = target.parentFile
        while (dir != null && dir != destination) {
            if (Files.isSymbolicLink(dir.toPath())) {
                val real = try {
                    dir.toPath().toRealPath().toString()
                } catch (e: IOException) {
                    throw EngineException(
                        EngineErrorCode.EXTRACT_FAILED,
                        "Unresolvable symlink in parent chain: $entryName",
                    )
                }
                if (real != rootPath && !real.startsWith(rootPath + File.separator)) {
                    throw EngineException(
                        EngineErrorCode.EXTRACT_FAILED,
                        "Path escapes rootfs via parent symlink: $entryName",
                    )
                }
            }
            dir = dir.parentFile
        }
    }

    // ---- safe delete ----

    /**
     * 删除同名条目。符号链接只删链接本身；目录逐项安全删除，
     * 禁用 File.deleteRecursively()（会跟进链接，方案 §3.3-2）。
     */
    fun deleteEntry(file: File): Boolean {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return true
        return when {
            Files.isSymbolicLink(file.toPath()) -> file.delete()
            file.isDirectory -> {
                var ok = true
                file.listFiles()?.forEach { child -> if (!deleteEntry(child)) ok = false }
                if (file.exists() && !file.delete()) ok = false
                ok
            }
            else -> file.delete()
        }
    }

    internal companion object {
        const val WHITEOUT_PREFIX = ".wh."
        const val WHITEOUT_OPAQUE = ".wh..wh..opq"
    }
}
