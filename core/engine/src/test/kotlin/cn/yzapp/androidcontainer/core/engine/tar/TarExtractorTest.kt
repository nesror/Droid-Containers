package cn.yzapp.androidcontainer.core.engine.tar

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import io.airlift.compress.zstd.ZstdOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val extractor = TarExtractor()

    /** 桌面 JVM 下 zstd-jni 原生库是否可用（AAR 制品不含桌面 .so 时为 false）。 */
    private fun zstdNativeAvailable(): Boolean = try {
        com.github.luben.zstd.util.Native.isLoaded() || run {
            // 触发一次需要原生的轻量调用，失败即视为不可用
            com.github.luben.zstd.Zstd.compress("probe".toByteArray())
            true
        }
    } catch (_: Throwable) {
        false
    }

    // ---- 基础解包 ----

    @Test
    fun `extracts regular file content into nested directory`() {
        val bytes = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("etc"),
                TarTestSupport.file("etc/os-release", "alpine\n"),
            ),
        )
        val dest = tmp.newFolder("rootfs")
        val result = extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        assertEquals("alpine\n", File(dest, "etc/os-release").readText())
        assertEquals(2, result.entryCount)
        assertEquals(7L, result.totalBytes)
    }

    @Test
    fun `hardlink resolves to source file (symlink fallback on fs without hardlink)`() {
        // 部分镜像的 tar 用 hardlink 条目表示同 inode 文件；
        // Android 文件系统不支持硬链接，必须降级为符号链接/拷贝，否则目标缺失导致 execve ENOENT
        val bytes = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("lib"),
                TarTestSupport.file("lib/libc.musl-aarch64.so.1", "MUSL"),
                TarTestSupport.Entry("lib/ld-musl-aarch64.so.1", TarTypeFlag.HARDLINK, linkName = "lib/libc.musl-aarch64.so.1"),
            ),
        )
        val dest = tmp.newFolder("rootfs")
        val result = extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        assertTrue(result.warnings.isEmpty())
        assertEquals("MUSL", File(dest, "lib/ld-musl-aarch64.so.1").readText())
    }

    @Test
    fun `extracts gzip layer`() {
        val tar = TarTestSupport.tarBytes(listOf(TarTestSupport.file("hello.txt", "hi")))
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(tar) }
        }.toByteArray()

        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(gz), LayerFormat.GZIP, dest)

        assertEquals("hi", File(dest, "hello.txt").readText())
    }

    @Test
    fun `extracts zstd layer via zstd-jni`() {
        // AAR 制品不携带桌面原生库（仅 Android jniLibs），本地 JVM 无 zstd 原生时跳过；
        // Android 真机路径已在 2026-09-18 HA 镜像拉取中验证
        org.junit.Assume.assumeTrue(zstdNativeAvailable())
        val tar = TarTestSupport.tarBytes(listOf(TarTestSupport.file("hello.txt", "hi-zstd")))
        val zst = ByteArrayOutputStream().also { out ->
            ZstdOutputStream(out).use { it.write(tar) }
        }.toByteArray()

        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(zst), LayerFormat.ZSTD, dest)

        assertEquals("hi-zstd", File(dest, "hello.txt").readText())
    }

    // ---- PAX / GNU longname ----

    @Test
    fun `pax linkpath over 100 bytes is not truncated`() {
        // n8n 镜像的 pnpm 布局：符号链接目标 > 100 字节，只存在于 PAX linkpath 记录中
        val name = "usr/local/lib/node_modules/n8n/node_modules/@n8n/backend-common"
        val longTarget = "../.pnpm/@n8n+backend-common@file++++home+runner+_work+n8n+n8n+packages+@n8n+backend-common/node_modules/@n8n/backend-common"
        assertTrue(longTarget.length > 100)
        val bytes = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.Entry(name, TarTypeFlag.SYMLINK, linkName = longTarget.take(100), paxLinkpath = longTarget),
            ),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        val link = java.nio.file.Files.readSymbolicLink(File(dest, name).toPath()).toString()
        assertEquals(longTarget, link)
    }

    @Test
    fun `pax multibyte filename is not sliced incorrectly`() {
        val name = "etc/配置文件-γλώσσα-日本語.conf"
        val bytes = TarTestSupport.tarBytes(
            listOf(TarTestSupport.Entry(name, paxPath = name, content = "ok".toByteArray())),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        val extracted = File(dest, name)
        assertTrue(extracted.isFile)
        assertEquals("ok", extracted.readText())
    }

    @Test
    fun `gnu longname entries are merged`() {
        val longName = "very/${"long/".repeat(30)}path/file.txt" // > 100 chars
        val bytes = TarTestSupport.tarBytes(listOf(TarTestSupport.file(longName, "deep")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        assertEquals("deep", File(dest, longName).readText())
    }

    // ---- 同名覆盖不跟进符号链接（EROFS 场景）----

    @Test
    fun `later layer overwrites symlink with real file without following it`() {
        val layer1 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.file("bin/busybox", "real-binary"),
                TarTestSupport.symlink("usr/bin/nslookup", "/bin/busybox"),
            ),
        )
        val layer2 = TarTestSupport.tarBytes(
            listOf(TarTestSupport.file("usr/bin/nslookup", "upgraded-binary")),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        // 目标必须是真实文件且内容为第二层；busybox 未被破坏（未跟进 /bin/busybox 链接）
        assertEquals("upgraded-binary", File(dest, "usr/bin/nslookup").readText())
        assertEquals("real-binary", File(dest, "bin/busybox").readText())
        assertFalse(java.nio.file.Files.isSymbolicLink(File(dest, "usr/bin/nslookup").toPath()))
    }

    // ---- 后层目录条目不清空前层内容（2026-09-18 真机根因）----

    @Test
    fun `later layer directory entry does not wipe files from earlier layer`() {
        // 真机场景：apk 安装层 tar 重复携带 lib/、usr/bin/ 等父目录条目，
        // 目录条目按 OCI 语义只表示“确保目录存在”，删除只能由 whiteout 触发
        val layer1 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("lib"),
                TarTestSupport.file("lib/ld-musl-aarch64.so.1", "MUSL"),
                TarTestSupport.dir("usr/bin"),
                TarTestSupport.file("usr/bin/busybox", "bb"),
            ),
        )
        val layer2 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("lib"),
                TarTestSupport.file("lib/libcrypto.so.3", "CRYPTO"),
                TarTestSupport.dir("usr/bin"),
                TarTestSupport.file("usr/bin/mosquitto_pub", "pub"),
            ),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        assertEquals("MUSL", File(dest, "lib/ld-musl-aarch64.so.1").readText())
        assertEquals("CRYPTO", File(dest, "lib/libcrypto.so.3").readText())
        assertEquals("bb", File(dest, "usr/bin/busybox").readText())
        assertEquals("pub", File(dest, "usr/bin/mosquitto_pub").readText())
    }

    @Test
    fun `directory entry replaces existing file at same path`() {
        val layer1 = TarTestSupport.tarBytes(listOf(TarTestSupport.file("opt", "was-a-file")))
        val layer2 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("opt"),
                TarTestSupport.file("opt/data", "d"),
            ),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        assertTrue(File(dest, "opt").isDirectory)
        assertEquals("d", File(dest, "opt/data").readText())
    }

    // ---- whiteout ----

    @Test
    fun `whiteout deletes same-named entry`() {
        val layer1 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("dir"),
                TarTestSupport.file("dir/a.txt", "old"),
                TarTestSupport.file("dir/b.txt", "keep"),
            ),
        )
        val layer2 = TarTestSupport.tarBytes(listOf(TarTestSupport.whiteout("dir", "a.txt")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        assertFalse(File(dest, "dir/a.txt").exists())
        assertEquals("keep", File(dest, "dir/b.txt").readText())
    }

    @Test
    fun `opaque whiteout clears parent directory`() {
        val layer1 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("opt"),
                TarTestSupport.file("opt/old1", "1"),
                TarTestSupport.file("opt/old2", "2"),
            ),
        )
        val layer2 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("opt"),
                TarTestSupport.opaque("opt"),
                TarTestSupport.file("opt/new", "n"),
            ),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        assertFalse(File(dest, "opt/old1").exists())
        assertFalse(File(dest, "opt/old2").exists())
        assertEquals("n", File(dest, "opt/new").readText())
    }

    // ---- 路径越界防护 ----

    @Test
    fun `rejects dot-dot path escape`() {
        val bytes = TarTestSupport.tarBytes(listOf(TarTestSupport.file("../evil.txt", "x")))
        val dest = tmp.newFolder("rootfs")
        try {
            extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals(EngineErrorCode.EXTRACT_FAILED, e.code)
        }
    }

    @Test
    fun `rejects absolute path entries`() {
        val bytes = TarTestSupport.tarBytes(listOf(TarTestSupport.file("/abs/evil.txt", "x")))
        val dest = tmp.newFolder("rootfs")
        try {
            extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals(EngineErrorCode.EXTRACT_FAILED, e.code)
        }
    }

    @Test
    fun `rejects parent symlink escape writing outside rootfs`() {
        // 审查 P0-2 利用链：先放一个指向 rootfs 外的符号链接（相对路径逃逸——
        // 绝对 linkName 按镜像原值保留，宿主侧悬空、proot 在 rootfs 内重新解析，
        // 同样无法落到 rootfs 外），再用普通文件条目穿过它落盘
        val victim = tmp.newFolder("victim")
        val layer1 = TarTestSupport.tarBytes(listOf(TarTestSupport.symlink("esc", "../victim")))
        val layer2 = TarTestSupport.tarBytes(listOf(TarTestSupport.file("esc/secret.txt", "pwned")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        try {
            extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals(EngineErrorCode.EXTRACT_FAILED, e.code)
        }
        // rootfs 外不能有任何写入
        assertTrue(victim.listFiles()!!.isEmpty())
    }

    @Test
    fun `rejects dangling symlink in parent chain`() {
        // 父链里的符号链接指向不存在的路径：canonicalFile 会退化成「不解析链接」，
        // 看起来仍在 rootfs 内，而后续 mkdirs/FileOutputStream 会跟进链接写到外面
        // → 必须按「不可解析」处理并拒绝（比单纯的 canonical 前缀校验更严）
        val layer1 = TarTestSupport.tarBytes(
            listOf(TarTestSupport.symlink("esc", "../no-such-dir-xyz")),
        )
        val layer2 = TarTestSupport.tarBytes(listOf(TarTestSupport.file("esc/secret.txt", "pwned")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        try {
            extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals(EngineErrorCode.EXTRACT_FAILED, e.code)
        }
        // 链接目标从未被创建
        assertFalse(File(tmp.root, "no-such-dir-xyz").exists())
    }

    @Test
    fun `allows symlink pointing inside rootfs in parent chain`() {
        // 正向用例：父链里的链接若解析后仍在 rootfs 内（容器内常见布局），不得误拒
        val layer1 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("real"),
                TarTestSupport.symlink("alias", "real"),
            ),
        )
        val layer2 = TarTestSupport.tarBytes(listOf(TarTestSupport.file("alias/inside.txt", "ok")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        assertEquals("ok", File(dest, "real/inside.txt").readText())
    }

    @Test
    fun `absolute symlink target is kept verbatim`() {
        // docker 语义：链接目标原样保留；proot 把 rootfs 绑为 /，容器内解析
        // /bin/sh -> /bin/busybox 必须落在 rootfs 内（2026-09-30 真机回归：
        // 曾改写成宿主绝对路径导致 proot ENOENT，Alpine 系镜像全部起不来）
        // Windows 下 File("/x") 会被规范化成盘符路径，只验证 POSIX 语义
        org.junit.Assume.assumeFalse(System.getProperty("os.name")?.lowercase()?.contains("windows") == true)
        val bytes = TarTestSupport.tarBytes(listOf(TarTestSupport.symlink("etc-link", "/etc")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        val target = java.nio.file.Files.readSymbolicLink(File(dest, "etc-link").toPath()).toString()
        assertEquals("/etc", target)
    }

    @Test
    fun `whiteout with dotdot target name is skipped as warning`() {
        // ".wh.." 解析出 ".." 会让删除目标落到 parent 之外，必须丢弃而不是执行
        val layer1 = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.dir("dir"),
                TarTestSupport.file("dir/a.txt", "keep"),
            ),
        )
        val layer2 = TarTestSupport.tarBytes(listOf(TarTestSupport.whiteout("dir", "..")))
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(layer1), LayerFormat.TAR, dest)
        val result = extractor.extract(ByteArrayInputStream(layer2), LayerFormat.TAR, dest)

        assertEquals("keep", File(dest, "dir/a.txt").readText())
        assertTrue(result.warnings.isNotEmpty())
    }

    // ---- 执行位恢复 ----

    @Test
    fun `executable mode bits are restored for owner on posix filesystems`() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val bytes = TarTestSupport.tarBytes(
            listOf(
                TarTestSupport.file("bin/sh", "#!/bin/sh\n", mode = 0b111_101_101L), // 0755
                TarTestSupport.file("etc/data", "x", mode = 0b110_100_100L), // 0644
            ),
        )
        val dest = tmp.newFolder("rootfs")
        extractor.extract(ByteArrayInputStream(bytes), LayerFormat.TAR, dest)

        assertTrue(File(dest, "bin/sh").canExecute())
        assertFalse(File(dest, "etc/data").canExecute())
    }

    // ---- 层格式识别 ----

    @Test
    fun `layer format mapping accepts only supported media types`() {
        assertEquals(LayerFormat.TAR, layerFormatFromMediaType("application/vnd.oci.image.layer.v1.tar"))
        assertEquals(LayerFormat.GZIP, layerFormatFromMediaType("application/vnd.oci.image.layer.v1.tar+gzip"))
        assertEquals(LayerFormat.ZSTD, layerFormatFromMediaType("application/vnd.oci.image.layer.v1.tar+zstd"))
        assertEquals(LayerFormat.GZIP, layerFormatFromMediaType("application/vnd.docker.image.rootfs.diff.tar.gzip"))
        assertEquals(LayerFormat.TAR, layerFormatFromMediaType("application/vnd.docker.image.rootfs.diff.tar"))
        // estargz / zstd-chunked / 未知扩展必须拒绝
        assertEquals(null, layerFormatFromMediaType("application/vnd.oci.image.layer.nondistributable.v1.tar+gzip+esgz"))
        assertEquals(null, layerFormatFromMediaType("application/vnd.cncf.oras.image.config.v1+json"))
    }

    // ---- GNU base-256 大小 ----

    @Test
    fun `base256 encoded size is parsed correctly`() {
        val size = 0x1122334455L
        val h = ByteArray(512)
        h[124] = (0x80).toByte()
        // 值占 size 字段第 2..12 字节（125..135），大端
        var v = size
        for (i in 10 downTo 0) {
            h[125 + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        h[156] = TarTypeFlag.REGULAR
        val full = ByteArray(512 + ((512 - size % 512) % 512).toInt() + 1024)
        System.arraycopy(h, 0, full, 0, 512)
        val reader = TarReader(ByteArrayInputStream(full))
        val entry = reader.nextEntry()
        assertEquals(size, entry?.size)
        assertEquals(null, reader.nextEntry())
    }
}
