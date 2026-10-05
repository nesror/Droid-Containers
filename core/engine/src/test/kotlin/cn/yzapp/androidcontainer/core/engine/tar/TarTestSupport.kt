package cn.yzapp.androidcontainer.core.engine.tar

import java.io.ByteArrayOutputStream

/** 内存 tar 构造辅助（仅供单测）。 */
object TarTestSupport {

    class Entry(
        val name: String,
        val type: Byte = TarTypeFlag.REGULAR,
        val content: ByteArray = ByteArray(0),
        val mode: Long = 0b110_100_100L, // 0644
        val linkName: String = "",
        /** 非 null 时额外写入一条 PAX 扩展头覆盖 path。 */
        val paxPath: String? = null,
        /** 非 null 时写入 PAX linkpath 记录（>100 字节的符号链接目标场景）。 */
        val paxLinkpath: String? = null,
    )

    fun file(name: String, content: String = "", mode: Long = 0b110_100_100L) =
        Entry(name, TarTypeFlag.REGULAR, content.toByteArray(), mode)

    fun dir(name: String, mode: Long = 0b111_101_101L) =
        Entry(name, TarTypeFlag.DIRECTORY, ByteArray(0), mode)

    fun symlink(name: String, target: String) =
        Entry(name, TarTypeFlag.SYMLINK, linkName = target)

    fun whiteout(parent: String, target: String) =
        file("$parent/.wh.$target")

    fun opaque(parent: String) =
        file("$parent/.wh..wh..opq")

    fun tarBytes(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            val paxRecords = buildMap {
                entry.paxPath?.let { put("path", it) }
                entry.paxLinkpath?.let { put("linkpath", it) }
            }
            if (paxRecords.isNotEmpty()) out.write(paxEntryBytes(paxRecords))
            val headerName = entry.name.toByteArray(Charsets.UTF_8)
            if (headerName.size > 100) {
                // GNU longname：'L' 元数据条目 + 空名头
                out.write(gnuLongNameBytes(entry.name))
                out.write(headerBytes("", entry.type, entry.content.size.toLong(), entry.mode, entry.linkName))
            } else {
                out.write(headerBytes(entry.name, entry.type, entry.content.size.toLong(), entry.mode, entry.linkName))
            }
            out.write(entry.content)
            out.write(ByteArray(padding(entry.content.size.toLong())))
        }
        out.write(ByteArray(1024)) // 结束零块 ×2
        return out.toByteArray()
    }

    private fun gnuLongNameBytes(name: String): ByteArray {
        val nameBytes = (name + "\u0000").toByteArray(Charsets.UTF_8)
        return headerBytes("././@LongLink", TarTypeFlag.GNU_LONGNAME, nameBytes.size.toLong(), 0, "") +
            nameBytes + ByteArray(padding(nameBytes.size.toLong()))
    }

    private fun padding(size: Long): Int = (((512 - size % 512) % 512).toInt())

    private fun paxEntryBytes(records: Map<String, String>): ByteArray {
        fun recordBytes(key: String, value: ByteArray): Pair<Int, ByteArray> {
            // 记录长度 = 长度数字 + 空格 + "key=" + 值 + \n（全部按字节计）
            var len = 0
            var record: ByteArray
            do {
                len = "${len}".length + 1 + key.length + 1 + value.size + 1
                val digits = "$len"
                val buf = ByteArrayOutputStream()
                buf.write(digits.toByteArray(Charsets.ISO_8859_1))
                buf.write(' '.code)
                buf.write(key.toByteArray(Charsets.ISO_8859_1))
                buf.write('='.code)
                buf.write(value)
                buf.write('\n'.code)
                record = buf.toByteArray()
            } while (record.size != len)
            return len to record
        }

        val prepared = records.map { (k, v) -> recordBytes(k, v.toByteArray(Charsets.UTF_8)).second }
        val payload = prepared.reduce { a, b -> a + b }
        return headerBytes("PaxHeader", TarTypeFlag.PAX_EXTENDED, payload.size.toLong(), 0, "") + payload +
            ByteArray(padding(payload.size.toLong()))
    }

    private fun headerBytes(name: String, type: Byte, size: Long, mode: Long, linkName: String): ByteArray {
        val h = ByteArray(512)
        fun put(bytes: ByteArray, offset: Int, limit: Int) {
            bytes.copyInto(h, offset, 0, minOf(bytes.size, limit))
        }
        put(name.toByteArray(Charsets.UTF_8), 0, 100)
        put(octal(mode, 8), 100, 8)
        put(octal(0, 8), 108, 8)
        put(octal(0, 8), 116, 8)
        put(octal(size, 12), 124, 12)
        put(octal(0, 12), 136, 12)
        put("        ".toByteArray(), 148, 8) // 校验和先置空格
        h[156] = type
        put(linkName.toByteArray(Charsets.UTF_8), 157, 100)
        put("ustar".toByteArray(), 257, 6)
        put("00".toByteArray(), 263, 2)
        var sum = 0L
        for (b in h) sum += b.toLong() and 0xFF
        put(octal(sum, 7) + byteArrayOf(0, ' '.code.toByte()), 148, 8)
        return h
    }

    private fun octal(value: Long, width: Int): ByteArray {
        val s = java.lang.Long.toOctalString(value)
        val padded = s.padStart(width - 1, '0')
        return padded.toByteArray(Charsets.ISO_8859_1)
    }
}
