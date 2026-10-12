package cn.yzapp.androidcontainer.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ContainerDns] 守门测试：容器 DNS 解析与 resolv.conf 生成。
 * 背景（2026-10 用户反馈）：默认 1.1.1.1 在部分运营商网络被拒收，alist 解析
 * drive.quark.cn 报 `read: connection refused`。现空设置跟随系统 DNS。
 */
class ContainerDnsTest {

    @Test
    fun explicitSettingWinsAndSupportsMultipleServers() {
        assertEquals(
            listOf("223.5.5.5", "119.29.29.29"),
            ContainerDns.serversFromSetting("223.5.5.5, 119.29.29.29", listOf("192.168.1.1")),
        )
        // 空白/分号均可作分隔符
        assertEquals(
            listOf("223.5.5.5", "119.29.29.29"),
            ContainerDns.serversFromSetting("223.5.5.5;\n119.29.29.29", emptyList()),
        )
    }

    @Test
    fun explicitSettingFiltersLoopbackAndCapsAtMaxNs() {
        assertEquals(
            listOf("223.5.5.5", "119.29.29.29", "8.8.8.8"),
            ContainerDns.serversFromSetting("223.5.5.5 119.29.29.29 8.8.8.8 9.9.9.9", emptyList()),
        )
        assertEquals(
            listOf("223.5.5.5"),
            ContainerDns.serversFromSetting("127.0.0.1 223.5.5.5 ::1", emptyList()),
        )
    }

    @Test
    fun emptySettingFollowsSystemDnsIpv4First() {
        assertEquals(
            listOf("192.168.1.1", "240e:56:4000:8000::11"),
            ContainerDns.serversFromSetting("", listOf("240e:56:4000:8000::11", "192.168.1.1")),
        )
    }

    @Test
    fun emptySettingFiltersLoopbackSystemDns() {
        assertEquals(
            listOf("192.168.1.1"),
            ContainerDns.serversFromSetting("", listOf("127.0.0.1", "::1", "192.168.1.1")),
        )
    }

    @Test
    fun noSystemDnsFallsBackToPublicResolvers() {
        assertEquals(ContainerDns.FALLBACK_SERVERS, ContainerDns.serversFromSetting("", emptyList()))
        assertEquals(ContainerDns.FALLBACK_SERVERS, ContainerDns.serversFromSetting("", listOf("127.0.0.1")))
    }

    @Test
    fun resolvConfContentWritesOneNameserverPerLine() {
        assertEquals("nameserver 223.5.5.5\n", ContainerDns.resolvConfContent(listOf("223.5.5.5")))
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 1.1.1.1\n",
            ContainerDns.resolvConfContent(listOf("223.5.5.5", "1.1.1.1")),
        )
        // 超过 MAXNS 的服务器被截断
        assertEquals(
            "nameserver 223.5.5.5\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            ContainerDns.resolvConfContent(listOf("223.5.5.5", "1.1.1.1", "8.8.8.8", "9.9.9.9")),
        )
    }
}
