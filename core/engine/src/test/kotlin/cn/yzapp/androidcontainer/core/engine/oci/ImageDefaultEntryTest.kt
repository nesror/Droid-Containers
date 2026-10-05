package cn.yzapp.androidcontainer.core.engine.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageDefaultEntryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(content: String): File = tmp.newFile().apply { writeText(content) }

    @Test
    fun `entrypoint plus cmd yields docker semantics`() {
        val f = write(
            """{"architecture":"arm64",
                "config":{"Entrypoint":["/docker-entrypoint.sh"],"Cmd":["nginx","-g","daemon off;"]}}""",
        )
        assertEquals(
            listOf("/docker-entrypoint.sh", "nginx", "-g", "daemon off;"),
            ImageDefaultEntry.argvFromConfig(f),
        )
    }

    @Test
    fun `cmd only yields cmd`() {
        val f = write("""{"config":{"Cmd":["redis-server","--save",""]}}""")
        assertEquals(listOf("redis-server", "--save", ""), ImageDefaultEntry.argvFromConfig(f))
    }

    @Test
    fun `env from config parses K=V pairs and survives malformed input`() {
        val f = write(
            """{"config":{"Env":["HOME=/var/syncthing","STHOMEDIR=/var/syncthing/config","BROKEN"]}}""",
        )
        assertEquals(
            mapOf("HOME" to "/var/syncthing", "STHOMEDIR" to "/var/syncthing/config"),
            ImageDefaultEntry.envFromConfig(f),
        )
        assertTrue(ImageDefaultEntry.envFromConfig(write("nope")).isEmpty())
        assertTrue(ImageDefaultEntry.envFromConfig(File(tmp.root, "nope")).isEmpty())
    }

    @Test
    fun `working dir from config honours absolute path and rejects the rest`() {
        val f = write("""{"config":{"WorkingDir":"/application/"}}""")
        assertEquals("/application", ImageDefaultEntry.workingDirFromConfig(f))
        // 空串 / 相对路径 / 缺字段 / 非 JSON / 文件缺失一律 null（回落引擎默认 cwd）
        assertEquals(null, ImageDefaultEntry.workingDirFromConfig(write("""{"config":{"WorkingDir":""}}""")))
        assertEquals(null, ImageDefaultEntry.workingDirFromConfig(write("""{"config":{"WorkingDir":"app"}}""")))
        assertEquals(null, ImageDefaultEntry.workingDirFromConfig(write("""{"config":{}}""")))
        assertEquals(null, ImageDefaultEntry.workingDirFromConfig(write("not json at all")))
        assertEquals(null, ImageDefaultEntry.workingDirFromConfig(File(tmp.root, "nope/config")))
    }

    @Test
    fun `missing fields or malformed file fall back to empty`() {
        assertTrue(ImageDefaultEntry.argvFromConfig(write("""{"config":{}}""")).isEmpty())
        assertTrue(ImageDefaultEntry.argvFromConfig(write("""{"architecture":"arm64"}""")).isEmpty())
        assertTrue(ImageDefaultEntry.argvFromConfig(write("not json at all")).isEmpty())
        assertTrue(ImageDefaultEntry.argvFromConfig(tmp.newFile().apply { writeText("") }).isEmpty())
        // 文件不存在（旧拉取的镜像没有 config 落盘）
        assertTrue(ImageDefaultEntry.argvFromConfig(File(tmp.root, "nope/config")).isEmpty())
    }
}
