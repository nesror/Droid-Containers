package cn.yzapp.androidcontainer.core.common

import java.net.NetworkInterface

/**
 * 本机局域网地址（M8 设置页展示 + Web 控制台地址拼接）。
 * 纯 JVM 实现（NetworkInterface 在 Android/JVM 均可用），只取非回环 IPv4。
 */
object LanAddress {

    fun allIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && it.address.size == 4 }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** 首个局域网 IPv4；无网络时返回 null。 */
    fun firstIpv4(): String? = allIpv4().firstOrNull()
}
