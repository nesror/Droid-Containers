package cn.yzapp.androidcontainer.core.engine.load

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class DockerLoadImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------ 用例

    @Test
    fun `legacy layout with plain layers is imported`() = runBlocking {
        val layer = tarOf(tarEntry("hello.txt", "hi".toByteArray()))
        val config = """{"config":{"Cmd":["/bin/sh"]}}""".toByteArray()
        val manifest = """
            [{"Config":"deadbeef/json","RepoTags":["my/image:1.0"],
              "Layers":["deadbeef/layer.tar"]}]
        """.trimIndent().toByteArray()
        val archive = tarOf(
            tarEntry("manifest.json", manifest),
            tarEntry("deadbeef/layer.tar", layer),
            tarEntry("deadbeef/json", config),
        )

        val loaded = DockerLoadImporter(tmp.newFolder("engine")).load(write(archive))

        assertEquals("my/image:1.0", loaded.ref)
        assertEquals("hi", File(loaded.rootfs, "hello.txt").readText())
        assertEquals(config.decodeToString(), File(loaded.rootfs.parentFile, "config").readText())
    }

    @Test
    fun `gzip sniffed layer and oci blobs layout is imported`() = runBlocking {
        val plainLayer = tarOf(tarEntry("bin/tool", "x".toByteArray()))
        val gzLayer = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(plainLayer) }
            out.toByteArray()
        }
        val config = """{"config":{"Cmd":["/bin/tool"]}}""".toByteArray()
        val manifest = """
            [{"Config":"blobs/sha256/ccc","RepoTags":["oci/image:latest"],
              "Layers":["blobs/sha256/aaa","blobs/sha256/bbb"]}]
        """.trimIndent().toByteArray()
        val archive = tarOf(
            tarEntry("oci-layout", "imageIndex".toByteArray()),
            tarEntry("manifest.json", manifest),
            tarEntry("blobs/sha256/aaa", plainLayer),
            tarEntry("blobs/sha256/bbb", gzLayer), // gzip 层：按魔数嗅探
            tarEntry("blobs/sha256/ccc", config),
        )

        val loaded = DockerLoadImporter(tmp.newFolder("engine")).load(write(archive))

        assertEquals("oci/image:latest", loaded.ref)
        assertEquals("x", File(loaded.rootfs, "bin/tool").readText())
    }

    @Test
    fun `whole archive gzip is unpacked`() = runBlocking {
        val layer = tarOf(tarEntry("a.txt", "A".toByteArray()))
        val manifest = """
            [{"Config":"x/json","RepoTags":["gz/image:v1"],"Layers":["x/layer.tar"]}]
        """.trimIndent().toByteArray()
        val plain = tarOf(
            tarEntry("manifest.json", manifest),
            tarEntry("x/layer.tar", layer),
            tarEntry("x/json", "{}".toByteArray()),
        )
        val gzipped = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(plain) }
            out.toByteArray()
        }

        val loaded = DockerLoadImporter(tmp.newFolder("engine")).load(write(gzipped, "docker-save.tar.gz"))

        assertEquals("gz/image:v1", loaded.ref)
        assertEquals("A", File(loaded.rootfs, "a.txt").readText())
    }

    @Test
    fun `no repo tags falls back to imported ref`() = runBlocking {
        val layer = tarOf(tarEntry("f.txt", "1".toByteArray()))
        val manifest = """
            [{"Config":"id123/json","RepoTags":[],"Layers":["id123/layer.tar"]}]
        """.trimIndent().toByteArray()
        val archive = tarOf(
            tarEntry("manifest.json", manifest),
            tarEntry("id123/layer.tar", layer),
            tarEntry("id123/json", "{}".toByteArray()),
        )

        val loaded = DockerLoadImporter(tmp.newFolder("engine")).load(write(archive))

        assertEquals("imported:id123", loaded.ref)
    }

    @Test
    fun `missing manifest is rejected as unsupported`() = runBlocking {
        val archive = tarOf(tarEntry("random.txt", "junk".toByteArray()))
        try {
            DockerLoadImporter(tmp.newFolder("engine")).load(write(archive))
            fail("expected MANIFEST_UNSUPPORTED")
        } catch (e: EngineException) {
            assertEquals(EngineErrorCode.MANIFEST_UNSUPPORTED, e.code)
        }
    }

    // ------------------------------------------------------------ docker save tar 构造

    private fun write(bytes: ByteArray, name: String = "docker-save.tar"): File {
        val file = File(tmp.root, name)
        file.writeBytes(bytes)
        return file
    }

    /** 拼接多个条目为完整 tar（含双零块结尾）。 */
    private fun tarOf(vararg entries: ByteArray): ByteArray = entries.reduce { acc, bytes -> acc + bytes } +
        ByteArray(1024)

    /** 单个 ustar 条目（短文件名、mode 0644）。 */
    private fun tarEntry(name: String, data: ByteArray): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.UTF_8).copyInto(header)
        "0000644\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(header, 100)
        "0000000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(header, 108)
        "0000000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(header, 116)
        data.size.toString(8).padStart(11, '0').toByteArray(Charsets.ISO_8859_1).copyInto(header, 124)
        header[135] = 0
        "00000000000".toByteArray(Charsets.ISO_8859_1).copyInto(header, 136) // mtime
        header[147] = 0
        header[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray(Charsets.ISO_8859_1).copyInto(header, 257)
        for (i in 148..155) header[i] = ' '.code.toByte()
        val checksum = header.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }
        val checksumField = (checksum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray(Charsets.ISO_8859_1)
        checksumField.copyInto(header, 148)
        val payload = data + ByteArray(((512 - data.size % 512) % 512))
        return header + payload
    }
}
