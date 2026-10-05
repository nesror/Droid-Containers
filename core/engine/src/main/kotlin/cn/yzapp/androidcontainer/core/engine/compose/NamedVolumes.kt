package cn.yzapp.androidcontainer.core.engine.compose

import cn.yzapp.androidcontainer.core.engine.proot.BindMount
import java.io.File

/**
 * named volume 支持（compose `volumes:` 语义的 proot 等价物）：
 *
 * - **named volume**（`dbdata:/var/lib/db`）→ `engineDir/volumes/<name>/` 目录
 *   bind 进容器。数据在 App 私有沙箱内，容器/镜像删除不影响——解决「删容器丢数据」。
 * - **bind 路径**（`/abs:/path`）→ 永不挂载：恶意 compose 可 bind App 私有目录
 *   摸 token/DB，且 scoped storage 下公共目录 App 无权读（评估文档 §1.3 双重否决）。
 * - **匿名**（`/path` 或 `path`）→ 不挂载（docker 语义为临时卷，无对应价值）。
 *
 * **老数据迁移 = docker 的 copy-on-first-use 语义**：volume 目录为空且 rootfs 内
 * 对应容器路径已有数据（历史版本数据寄生在 rootfs）时，首次挂载把数据拷入 volume，
 * 之后 bind 生效即接管；rootfs 原件保留（对齐 docker：镜像内容不被 volume 摘除）。
 * 老用户只需在 compose 里补上 `name:/原路径` 声明，up 一次即完成迁移，无手工操作。
 *
 * 纯 JVM 实现，可直接单测。
 */
object NamedVolumes {

    /** 服务内单条 volume 短语法的分类。 */
    enum class VolumeEntryKind { NAMED, BIND_PATH, ANONYMOUS }

    data class VolumeEntry(val kind: VolumeEntryKind, val name: String?, val containerPath: String?, val raw: String)

    fun parseEntry(raw: String): VolumeEntry {
        val text = raw.trim()
        if (':' !in text) {
            // `- /var/lib/data`（容器路径）或 `- data`（名字）：均为匿名（无挂载点无法挂）
            return VolumeEntry(VolumeEntryKind.ANONYMOUS, name = null, containerPath = null, raw = raw)
        }
        val name = text.substringBefore(':').trim()
        val containerPath = text.substringAfter(':').substringBefore(':').trim()
        // 宿主侧路径先判：绝对路径、相对路径（./、../）、windows 盘符/反斜杠一律 bind 类，
        // 不看 containerPath（否则 "./data:/x" 会因容器路径不以 / 开头被误判为匿名）
        if (name.startsWith("/") || name.startsWith(".") || text.contains('\\')) {
            return VolumeEntry(VolumeEntryKind.BIND_PATH, name = null, containerPath = null, raw = raw)
        }
        if (containerPath.isEmpty() || !containerPath.startsWith("/")) {
            return VolumeEntry(VolumeEntryKind.ANONYMOUS, name = null, containerPath = null, raw = raw)
        }
        return VolumeEntry(VolumeEntryKind.NAMED, name = name, containerPath = containerPath, raw = raw)
    }

    /** named volume 在引擎目录下的真实存储位置。 */
    fun volumeDirFor(engineDir: File, name: String): File =
        File(engineDir, "volumes/" + sanitize(name))

    /** volume 名限定 docker 合法字符，其余替换为 `-`；`.`/`..` 这类纯点名折叠为 `volume`（防目录穿越）。 */
    fun sanitize(name: String): String {
        val mapped = name.trim().trimEnd('/', '\\').map { ch ->
            if (ch.isLetterOrDigit() && ch.code < 128 || ch in "_.-") ch else '-'
        }.joinToString("")
        return if (mapped.isEmpty() || mapped == "." || mapped == "..") "volume" else mapped
    }

    /**
     * copy-on-first-use（老数据迁移）：volume 为空且 rootfs 内 [containerPath] 有内容时，
     * 递归拷入 volume（文件 + 符号链接原样保留）。返回是否发生拷贝。
     * 幂等：volume 非空（用户已有数据）直接跳过。
     * volume 目录无论是否有老数据都会就绪（空目录也要建，否则 proot bind 报
     * "can't sanitize binding: No such file or directory"，容器内挂载点不可写）。
     */
    fun copyOnFirstUse(rootfs: File, containerPath: String, volumeDir: File): Boolean {
        volumeDir.mkdirs()
        val source = File(rootfs, containerPath.trimStart('/'))
        if (!source.exists()) return false
        if (volumeDir.list()?.isNotEmpty() == true) return false // 已有数据，以 volume 为准
        copyRecursively(source, volumeDir)
        return true
    }

    private fun copyRecursively(source: File, target: File) {
        val linkTarget = runCatching {
            java.nio.file.Files.readSymbolicLink(source.toPath()).toString()
        }.getOrNull()
        if (linkTarget != null) {
            runCatching {
                java.nio.file.Files.createSymbolicLink(target.toPath(), File(linkTarget).toPath())
            }
            return
        }
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child -> copyRecursively(child, File(target, child.name)) }
        } else if (source.isFile) {
            source.copyTo(target, overwrite = true)
        }
    }

    /** 把持久化的 mountsJson 条目（`host:container[:ro]`）还原为 bind（启动/终端共用）。 */
    fun decodeMounts(entries: List<String>): List<BindMount> =
        entries.mapNotNull { raw ->
            val host = raw.substringBefore(':')
            val rest = raw.substringAfter(':', "")
            if (host.isEmpty() || rest.isEmpty()) return@mapNotNull null
            val containerPath = rest.substringBefore(':')
            val readOnly = rest.substringAfter(':', "") == "ro"
            BindMount(hostPath = File(host), containerPath = containerPath, readOnly = readOnly)
        }

    fun encodeMounts(binds: List<BindMount>): List<String> =
        binds.map { bind ->
            "${bind.hostPath.path}:${bind.containerPath}" + if (bind.readOnly) ":ro" else ""
        }
}
