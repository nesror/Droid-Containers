package cn.yzapp.androidcontainer.core.engine.tar

import java.io.EOFException
import java.io.InputStream

/** tar 条目类型常量（POSIX typeflag）。 */
object TarTypeFlag {
    const val REGULAR: Byte = '0'.code.toByte()
    const val HARDLINK: Byte = '1'.code.toByte()
    const val SYMLINK: Byte = '2'.code.toByte()
    const val DIRECTORY: Byte = '5'.code.toByte()
    const val GNU_LONGNAME: Byte = 'L'.code.toByte()
    const val GNU_LONGLINK: Byte = 'K'.code.toByte()
    const val PAX_EXTENDED: Byte = 'x'.code.toByte()
    const val PAX_GLOBAL: Byte = 'g'.code.toByte()
}

data class TarEntry(
    val name: String,
    val type: Byte,
    val size: Long,
    val mode: Long,
    val linkName: String,
) {
    val isRegularFile: Boolean get() = type == TarTypeFlag.REGULAR || type == 0.toByte()
    val isDirectory: Boolean get() = type == TarTypeFlag.DIRECTORY
    val isSymlink: Boolean get() = type == TarTypeFlag.SYMLINK
    val isHardlink: Boolean get() = type == TarTypeFlag.HARDLINK

    /** mode 含任一执行位（0111）则解压后需为 owner 补执行位（方案 §3.3-5）。 */
    val hasAnyExecuteBit: Boolean get() = (mode and 0b000_001_001L) != 0L
}

/**
 * 流式 tar 读取器：
 * - PAX 扩展头按字节切分后整段 UTF-8 解码，避免多字节文件名被切错（方案 §3.3-4）；
 * - GNU longname（'L'）/longlink（'K'）合并到下一条目；
 * - 大小字段支持 GNU base-256 编码（Alpine 层中 >8GB 条目使用）。
 */
class TarReader(private val input: InputStream) {

    private val header = ByteArray(BLOCK)

    /** 返回下一个真实条目；流结束返回 null。元数据条目（PAX/longname）自动消费并合并。 */
    fun nextEntry(): TarEntry? {
        var paxPath: String? = null
        var paxLinkpath: String? = null
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            if (!readBlock()) return null // EOF
            if (isZeroBlock(header)) {
                // 结束标志：应有两个连续零块，再读一块以对齐后续（多层 tar 顺序拼接场景）
                readBlock()
                return null
            }

            val type = header[156]
            val size = parseSize(header, 124, 12)
            when (type) {
                TarTypeFlag.PAX_EXTENDED, TarTypeFlag.PAX_GLOBAL -> {
                    val records = readPayloadBytes(size)
                    val map = parsePaxRecords(records)
                    if (!map["path"].isNullOrEmpty()) paxPath = map["path"]
                    // linkpath：>100 字节的符号链接目标只在 PAX 头里携带（pnpm 铺开的
                    // node_modules 大量此类条目，丢失会导致镜像启动时模块解析失败）
                    if (!map["linkpath"].isNullOrEmpty()) paxLinkpath = map["linkpath"]
                }
                TarTypeFlag.GNU_LONGNAME -> gnuLongName = readPayloadBytes(size).toString(Charsets.UTF_8).trimEnd('\u0000')
                TarTypeFlag.GNU_LONGLINK -> gnuLongLink = readPayloadBytes(size).toString(Charsets.UTF_8).trimEnd('\u0000')
                else -> {
                    val rawName = parseEntryName(header)
                    return TarEntry(
                        name = paxPath ?: gnuLongName ?: rawName,
                        type = type,
                        size = size,
                        mode = parseOctal(header, 100, 8),
                        linkName = gnuLongLink ?: paxLinkpath ?: parseString(header, 157, 100),
                    )
                }
            }
        }
    }

    /** 读取当前条目的数据体（返回的流读取 size 字节后自动到 EOF，含块对齐填充）。 */
    fun entryDataStream(entry: TarEntry): InputStream =
        LimitedInputStream(input, entry.size)

    /** 跳过当前条目数据体。 */
    fun skipEntryData(entry: TarEntry) {
        var remaining = entry.size
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() == -1) throw EOFException("Unexpected EOF in tar entry data")
                remaining -= 1
            } else {
                remaining -= n
            }
        }
        skipPadding(entry.size)
    }

    /** 条目数据流被完整消费后调用，跳过 512 字节块对齐填充。 */
    fun skipPaddingAfter(entry: TarEntry) = skipPadding(entry.size)

    private fun skipPadding(size: Long) {
        val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        var remaining = pad.toLong()
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() == -1) return
                remaining -= 1
            } else {
                remaining -= n
            }
        }
    }

    private fun readPayloadBytes(size: Long): ByteArray {
        // 元数据条目（PAX header / GNU long name）声明多大就分配多大 → 恶意 tar 可写
        // Long.MAX_VALUE 直接 OOM（审查 P1-8）。真实元数据远小于此上限
        require(size in 0..MAX_META_BYTES) { "tar metadata entry too large: $size" }
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n == -1) throw EOFException("Unexpected EOF in tar metadata entry")
            off += n
        }
        skipPadding(size)
        return data
    }

    /** 读满 512 字节；不足一块视为流结束。 */
    private fun readBlock(): Boolean {
        var off = 0
        while (off < BLOCK) {
            val n = input.read(header, off, BLOCK - off)
            if (n == -1) return false
            off += n
        }
        return true
    }

    /**
     * PAX 记录："%d <key>=<value>\n"，前导 %d 是整条记录的【字节长度】。
     * 必须先按字节定位记录边界，再对 value 区间做 UTF-8 解码；
     * 若先解码再 substring 会把多字节字符切错（方案 §3.3-4）。
     */
    internal fun parsePaxRecords(data: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var pos = 0
        while (pos < data.size && data[pos] != 0.toByte()) {
            var sp = pos
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val len = String(data, pos, sp - pos, Charsets.ISO_8859_1).trim().toIntOrNull() ?: break
            val recordEnd = pos + len
            if (recordEnd > data.size || recordEnd <= sp + 1) break
            // 区间 [sp+1, recordEnd-1)：去掉记录末尾的 \n
            val kv = String(data, sp + 1, recordEnd - 1 - (sp + 1), Charsets.UTF_8)
            val eq = kv.indexOf('=')
            if (eq > 0) map[kv.substring(0, eq)] = kv.substring(eq + 1)
            pos = recordEnd
        }
        return map
    }

    private fun parseEntryName(header: ByteArray): String {
        val name = parseString(header, 0, 100)
        val prefix = parseString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    /** 8 进制或 GNU base-256 编码的数字字段。 */
    private fun parseSize(header: ByteArray, offset: Int, length: Int): Long {
        if ((header[offset].toInt() and 0x80) != 0) {
            // GNU base-256：首字节最高位为符号/扩展标志，其余为大端无符号值（正数）
            var value = (header[offset].toLong() and 0x7F)
            for (i in 1 until length) {
                value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            }
            return value
        }
        return parseOctal(header, offset, length)
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var started = false
        for (i in 0 until length) {
            val b = header[offset + i].toInt() and 0xFF
            if (b == 0 || b == ' '.code) {
                if (started) break else continue
            }
            require(b in '0'.code..'7'.code) { "Invalid octal digit at offset $offset: $b" }
            started = true
            value = (value shl 3) or (b - '0'.code).toLong()
        }
        return value
    }

    private fun parseString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        for (b in block) if (b != 0.toByte()) return false
        return true
    }

    private class LimitedInputStream(
        private val upstream: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = upstream.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = upstream.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    internal companion object {
        const val BLOCK = 512

        /** 元数据条目（PAX/GNU long name）大小上限：防声明超大 size 的 OOM。 */
        const val MAX_META_BYTES = 16L * 1024 * 1024
    }
}
