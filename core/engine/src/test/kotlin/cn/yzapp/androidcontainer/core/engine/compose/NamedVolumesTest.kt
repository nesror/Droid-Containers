package cn.yzapp.androidcontainer.core.engine.compose

import cn.yzapp.androidcontainer.core.engine.proot.BindMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NamedVolumesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------ 条目分类

    @Test
    fun `named entry is parsed`() {
        val entry = NamedVolumes.parseEntry("dbdata:/var/lib/db")
        assertEquals(NamedVolumes.VolumeEntryKind.NAMED, entry.kind)
        assertEquals("dbdata", entry.name)
        assertEquals("/var/lib/db", entry.containerPath)
    }

    @Test
    fun `absolute host path is bind and never mounted`() {
        val entry = NamedVolumes.parseEntry("/sdcard/Download:/data")
        assertEquals(NamedVolumes.VolumeEntryKind.BIND_PATH, entry.kind)
    }

    @Test
    fun `relative and windows host paths are bind`() {
        assertEquals(NamedVolumes.VolumeEntryKind.BIND_PATH, NamedVolumes.parseEntry("./data:/x").kind)
        assertEquals(NamedVolumes.VolumeEntryKind.BIND_PATH, NamedVolumes.parseEntry("C:\\data:/x").kind)
    }

    @Test
    fun `entries without mount point are anonymous`() {
        assertEquals(NamedVolumes.VolumeEntryKind.ANONYMOUS, NamedVolumes.parseEntry("/var/lib/data").kind)
        assertEquals(NamedVolumes.VolumeEntryKind.ANONYMOUS, NamedVolumes.parseEntry("dbdata").kind)
        assertEquals(NamedVolumes.VolumeEntryKind.ANONYMOUS, NamedVolumes.parseEntry("dbdata:nopath").kind)
    }

    @Test
    fun `volume name is sanitized against traversal`() {
        assertEquals("app-data", NamedVolumes.sanitize("app data"))
        // '.' 与 '-' 是合法字符，但纯点名必须折叠（否则 volumes/".." = 引擎目录）
        assertEquals("..-..", NamedVolumes.sanitize("../../"))
        assertEquals("volume", NamedVolumes.sanitize(""))
        assertEquals("volume", NamedVolumes.sanitize(".."))
        assertFalse(NamedVolumes.sanitize("../etc").contains('/'))
    }

    // ------------------------------------------------------------ copy-on-first-use（老数据迁移）

    @Test
    fun `existing rootfs data is migrated into empty volume`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "var/lib/db/sub").mkdirs()
        File(rootfs, "var/lib/db/old.txt").writeText("legacy")
        File(rootfs, "var/lib/db/sub/nested.bin").writeBytes(byteArrayOf(1, 2, 3))

        val volumeDir = tmp.newFolder("volumes").resolve("dbdata")
        val migrated = NamedVolumes.copyOnFirstUse(rootfs, "/var/lib/db", volumeDir)

        assertTrue(migrated)
        assertEquals("legacy", File(volumeDir, "old.txt").readText())
        assertTrue(File(volumeDir, "sub/nested.bin").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `volume with existing data wins and rootfs is not copied`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "data").mkdirs()
        File(rootfs, "data/old.txt").writeText("legacy")
        val volumeDir = tmp.newFolder("volumes").resolve("v").apply { mkdirs() }
        File(volumeDir, "user.txt").writeText("current")

        assertFalse(NamedVolumes.copyOnFirstUse(rootfs, "/data", volumeDir))
        assertEquals("current", File(volumeDir, "user.txt").readText())
        assertFalse(File(volumeDir, "old.txt").exists())
    }

    @Test
    fun `missing rootfs path is a no-op`() {
        val rootfs = tmp.newFolder("rootfs")
        val volumeDir = tmp.newFolder("volumes").resolve("v")
        assertFalse(NamedVolumes.copyOnFirstUse(rootfs, "/no/such", volumeDir))
    }

    @Test
    fun `symlinks are preserved as links`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "data").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(rootfs, "data/link").toPath(),
            File("/etc/hostname").toPath(),
        )
        val volumeDir = tmp.newFolder("volumes").resolve("v")

        assertTrue(NamedVolumes.copyOnFirstUse(rootfs, "/data", volumeDir))
        assertTrue(java.nio.file.Files.isSymbolicLink(File(volumeDir, "link").toPath()))
    }

    // ------------------------------------------------------------ binds 编码往返

    @Test
    fun `mounts encode decode round trip`() {
        val binds = listOf(
            BindMount(hostPath = File("/data/data/app/files/engine/volumes/db"), containerPath = "/var/lib/db"),
            BindMount(hostPath = File("/data/ro"), containerPath = "/mnt/ro", readOnly = true),
        )
        val decoded = NamedVolumes.decodeMounts(NamedVolumes.encodeMounts(binds))
        assertEquals(binds, decoded)
    }
}
