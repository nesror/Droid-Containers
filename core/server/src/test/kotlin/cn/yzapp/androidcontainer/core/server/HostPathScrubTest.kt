package cn.yzapp.androidcontainer.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 错误响应路径脱敏（审查 P1-5 引入，C-4 收紧）。
 *
 * 目的有二：宿主绝对路径必须被替换（否则未鉴权的 Docker 端口可枚举包名与存储布局）；
 * **容器内路径必须原样保留**——早期正则把裸 `/root`、`/home`、`/system` 也算作宿主前缀，
 * 会把容器里的 `/root/.cache` 替换成 `<host>/.cache`，直接把错误消息读坏。
 */
class HostPathScrubTest {

    @Test
    fun `scrubs app private storage paths`() {
        val message = "rootfs missing: /data/user/0/cn.yzapp.androidcontainer/files/engine/layers/x/rootfs"
        assertEquals("rootfs missing: <host>", scrubHostPaths(message))
        assertEquals("<host>", scrubHostPaths("/data/data/cn.yzapp.androidcontainer/files/x"))
    }

    @Test
    fun `scrubs shared storage and desktop paths`() {
        assertEquals("<host>", scrubHostPaths("/storage/emulated/0/Android/data/x"))
        assertEquals("<host>", scrubHostPaths("/storage/self/primary/x"))
        assertEquals("<host>", scrubHostPaths("/Users/nestor/ssd/project/x"))
    }

    @Test
    fun `keeps container internal paths intact`() {
        // 这些是容器内常见路径，绝不能被当成宿主路径
        assertEquals("/root/.cache/x", scrubHostPaths("/root/.cache/x"))
        assertEquals("/home/app/config.yml", scrubHostPaths("/home/app/config.yml"))
        assertEquals("/etc/ssl/certs/ca-certificates.crt", scrubHostPaths("/etc/ssl/certs/ca-certificates.crt"))
        assertEquals("/usr/lib/libz.so", scrubHostPaths("/usr/lib/libz.so"))
        assertEquals("/system/bin/sh", scrubHostPaths("/system/bin/sh"))
        assertEquals("/vendor/lib64/libc.so", scrubHostPaths("/vendor/lib64/libc.so"))
    }

    @Test
    fun `keeps mixed message readable`() {
        val message = "start failed: /root/app/main.sh not found under /data/user/0/cn.yzapp.androidcontainer/files"
        assertEquals("start failed: /root/app/main.sh not found under <host>", scrubHostPaths(message))
    }

    @Test
    fun `null message becomes empty string`() {
        assertEquals("", scrubHostPaths(null))
    }
}
