package cn.yzapp.androidcontainer.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VersionNumbers] 语义化版本对比守门测试（仅 cn 变体编译，`testCnDebugUnitTest`）。 */
class VersionNumbersTest {

    @Test
    fun parseBase_stripsVPrefixAndPrereleaseSuffix() {
        assertEquals("1.2.3", VersionNumbers.parseBase("v1.2.3"))
        assertEquals("1.2.3", VersionNumbers.parseBase("V1.2.3"))
        assertEquals("1.2.3", VersionNumbers.parseBase("v1.2.3-beta.1"))
        assertEquals("1.2.3", VersionNumbers.parseBase(" 1.2.3 "))
        assertEquals("1.2.3", VersionNumbers.parseBase("1.2.3-cn"))
    }

    @Test
    fun compare_ordersNumericSegments() {
        assertEquals(1, VersionNumbers.compare("1.2.10", "1.2.9"))
        assertEquals(-1, VersionNumbers.compare("1.2.9", "1.2.10"))
        assertEquals(1, VersionNumbers.compare("1.3.0", "1.2.99"))
        assertEquals(1, VersionNumbers.compare("2.0.0", "1.9.9"))
    }

    @Test
    fun compare_padsMissingSegmentsWithZero() {
        assertEquals(0, VersionNumbers.compare("1.2", "1.2.0"))
        assertEquals(1, VersionNumbers.compare("1.2.1", "1.2"))
        assertEquals(-1, VersionNumbers.compare("1.2", "1.2.1"))
    }

    @Test
    fun compare_equalVersionsReturnZero() {
        assertEquals(0, VersionNumbers.compare("1.2.3", "1.2.3"))
        assertEquals(0, VersionNumbers.compare("v1.2.3", "1.2.3"))
        assertEquals(0, VersionNumbers.compare("1.2.3-rc1", "1.2.3"))
    }

    @Test
    fun isNewer_matchesCompareSign() {
        assertTrue(VersionNumbers.isNewer("v1.2.4", "1.2.3"))
        assertTrue(VersionNumbers.isNewer("v2.0.0", "1.9.9"))
        assertFalse(VersionNumbers.isNewer("v1.2.3", "1.2.3"))
        assertFalse(VersionNumbers.isNewer("v1.2.2", "1.2.3"))
        // 预发布后缀被忽略：同基线不视为新版本
        assertFalse(VersionNumbers.isNewer("v1.2.3-rc1", "1.2.3"))
    }

    @Test
    fun compare_nonNumericSegmentsTreatedAsZero() {
        assertEquals(0, VersionNumbers.compare("1.2.x", "1.2.0"))
        assertEquals(1, VersionNumbers.compare("1.3", "1.2.x"))
    }
}
