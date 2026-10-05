package cn.yzapp.androidcontainer.core.engine.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runtime(nativeLibDir: File, engineDir: File) = ProotRuntime(nativeLibDir, engineDir)

    @Test
    fun `argv contains fake-root, link2symlink, kill-on-exit, workdir, rootfs and system binds`() {
        val nativeLib = tmp.newFolder("native")
        val engineDir = tmp.newFolder("engine")
        File(nativeLib, "libproot.so").writeText("fake")
        val rootfs = tmp.newFolder("rootfs")

        val spec = runtime(nativeLib, engineDir).launch(
            rootfs = rootfs,
            workdir = "/",
            command = listOf("/bin/sh", "-c", "echo hi"),
            userBinds = listOf(BindMount(File(engineDir, "vol"), "/data", readOnly = true)),
        )

        val argv = spec.argv
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("--link2symlink"))
        assertTrue(argv.contains("--kill-on-exit"))
        val w = argv.indexOf("-w")
        assertEquals("/", argv[w + 1])
        val r = argv.indexOf("-r")
        assertEquals(rootfs.path, argv[r + 1])
        assertTrue(argv.contains(File(engineDir, "tmp").path + ":/tmp"))
        // 运行时目录不 bind（遮镜像内子目录）：缺失时在 rootfs 内补建
        assertTrue(argv.none { it.endsWith(":/run") })
        assertTrue(argv.none { it.endsWith(":/var/log") })
        assertTrue(argv.none { it.endsWith(":/var/cache") })
        assertTrue(argv.none { it.endsWith(":/var/run") })
        assertTrue(File(rootfs, "run").isDirectory)
        assertTrue(File(rootfs, "var/log").isDirectory)
        assertTrue(File(rootfs, "var/cache").isDirectory)
        assertTrue(File(rootfs, "var/run").isDirectory)
        // 系统目录绑定：镜像进程依赖 /dev/null、/proc、/sys（rootfs 普通目录树没有设备节点）
        assertTrue(argv.contains("/dev:/dev"))
        assertTrue(argv.contains("/proc:/proc"))
        assertTrue(argv.contains("/sys:/sys"))
        assertTrue(File(rootfs, "dev").isDirectory)
        assertTrue(File(rootfs, "proc").isDirectory)
        assertTrue(File(rootfs, "sys").isDirectory)
        assertTrue(argv.contains(File(engineDir, "vol").path + ":/data:ro"))
        // proot CLI 不支持 `--` 终止符：命令直接追加在 argv 末尾
        assertFalse(argv.contains("--"))
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), argv.subList(argv.size - 3, argv.size))
    }

    @Test
    fun `environment injects container PATH, loader paths and seccomp off`() {
        val nativeLib = tmp.newFolder("native")
        val engineDir = tmp.newFolder("engine")
        File(nativeLib, "libproot.so").writeText("fake")

        val env = runtime(nativeLib, engineDir).launch(File(tmp.newFolder("rootfs").path), "/", listOf("x")).environment

        // 容器内必须有标准 PATH：宿主 Android PATH（/system/bin 等）在 proot 内找不到任何用户态命令
        assertEquals("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", env["PATH"])
        assertEquals(nativeLib.path, env["LD_LIBRARY_PATH"])
        assertEquals(File(nativeLib, "libproot_loader.so").path, env["PROOT_LOADER"])
        assertEquals(File(nativeLib, "libproot_loader32.so").path, env["PROOT_LOADER_32"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals(File(engineDir, "tmp").path, env["PROOT_TMP_DIR"])
    }

    @Test
    fun `missing proot binary reports runtimeMissing`() {
        val nativeLib = tmp.newFolder("native")
        val engineDir = tmp.newFolder("engine")
        try {
            runtime(nativeLib, engineDir).launch(File(tmp.newFolder("rootfs").path), "/", listOf("x"))
            org.junit.Assert.fail("expected EngineException")
        } catch (e: cn.yzapp.androidcontainer.core.model.EngineException) {
            assertEquals(cn.yzapp.androidcontainer.core.model.EngineErrorCode.RUNTIME_MISSING, e.code)
        }
    }

    @Test
    fun `s6 overlay entrypoint detected only when services exist`() {
        val rootfs = tmp.newFolder("rootfs")
        assertFalse(ProotRuntime.isS6OverlayEntrypoint(rootfs, "/init"))
        val service = File(rootfs, "etc/services.d/nginx")
        service.mkdirs()
        File(service, "run").writeText("#!/command/with-contenv sh\n")
        assertTrue(ProotRuntime.isS6OverlayEntrypoint(rootfs, "/init"))
        assertFalse(ProotRuntime.isS6OverlayEntrypoint(rootfs, "/entrypoint.sh"))
    }

    @Test
    fun `hasShell resolves absolute symlink target inside rootfs`() {
        val rootfs = tmp.newFolder("rootfs")
        // 无 bin/sh → false
        assertFalse(ProotRuntime.hasShell(rootfs))
        // 真实文件 → true
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        assertTrue(ProotRuntime.hasShell(rootfs))

        // Alpine 形态：sh 是指向 /bin/busybox 的绝对路径符号链接，
        // 宿主上目标不存在（悬空链接），须映射回 rootfs 内判定
        val alpine = tmp.newFolder("alpine")
        File(alpine, "bin").mkdirs()
        File(alpine, "bin/busybox").writeText("ELF")
        java.nio.file.Files.createSymbolicLink(
            File(alpine, "bin/sh").toPath(),
            java.nio.file.Path.of("/bin/busybox"),
        )
        assertTrue(ProotRuntime.hasShell(alpine))
    }

    @Test
    fun `parsePpid handles comm field containing spaces and brackets`() {
        assertEquals(1234, ContainerProcessManager.parsePpid("42 (proot: bin) S 1234 456 456 0"))
        assertEquals(5678, ContainerProcessManager.parsePpid("42 ((weird) name) R 5678 1 1 0"))
        assertEquals(1, ContainerProcessManager.parsePpid("9 (x) S 1 1 1"))
        assertEquals(null, ContainerProcessManager.parsePpid("9 (x) S"))
    }
}
