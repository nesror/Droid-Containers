package cn.yzapp.androidcontainer.core.common

import cn.yzapp.androidcontainer.core.model.HostAbi

/**
 * 由宿主支持的 ABI 列表推断运行平台。
 * 仅支持 64 位（arm64-v8a / x86_64），32 位设备返回 null（UI 层报 unsupportedDevice）。
 */
fun detectHostAbi(supportedAbis: List<String>): HostAbi? {
    for (abi in supportedAbis) {
        HostAbi.fromAndroidAbi(abi)?.let { return it }
    }
    return null
}

/**
 * 字节数的人类可读格式化（仪表盘/镜像列表共用）。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "未知"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format("%.1f %s", value, units[unit])
}
