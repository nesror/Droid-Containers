package cn.yzapp.androidcontainer.core.model

enum class ContainerStatus {
    CREATED,
    RUNNING,
    STOPPED,
    EXITED,
    FAILED,
}

/**
 * 容器元数据（对应 Room 表 containers，方案 §4）。
 */
data class ContainerInfo(
    val id: String,
    val name: String,
    val imageRef: String,
    val status: ContainerStatus,
    val ports: List<String> = emptyList(),
    val volumeMounts: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val autoStart: Boolean = false,
    val createdAt: Long = 0,
)
