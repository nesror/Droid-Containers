package cn.yzapp.androidcontainer.core.model

/**
 * 宿主 ABI 与 docker 平台标识的映射（方案 §3.2）。
 */
enum class HostAbi(val androidAbi: String, val dockerPlatform: String) {
    ARM64("arm64-v8a", "linux/arm64"),
    X86_64("x86_64", "linux/amd64");

    companion object {
        fun fromAndroidAbi(abi: String): HostAbi? = entries.find { it.androidAbi == abi }
    }
}
