package cn.yzapp.androidcontainer.core.model

/**
 * 拉取/安装状态机（方案 §6）：
 * idle → checkingEnv → pulling → extracting → creatingContainer → starting → ready
 * 失败可重试；用户取消进入 cancelled。
 */
enum class PullStage {
    IDLE,
    CHECKING_ENV,
    RESOLVING_MANIFEST,
    DOWNLOADING,
    EXTRACTING,
    CREATING_CONTAINER,
    STARTING,
    READY,
    FAILED,
    CANCELLED,
}

/**
 * 拉取进度，引擎层以 SharedFlow<PullProgress> 暴露（400ms 节流，阶段切换立即推送）。
 */
data class PullProgress(
    val imageRef: String,
    val stage: PullStage,
    val currentLayer: Int = 0,
    val totalLayers: Int = 0,
    /** 已完成下载的层数（含缓存命中层），下载中恒小于 [totalLayers]。 */
    val completedLayers: Int = 0,
    /** 当前层已下载字节（[currentLayer] 这一层内部的进度）。 */
    val downloadedBytes: Long = 0,
    /** 当前层总字节。 */
    val totalBytes: Long = 0,
    /** 全部层累计已下载字节（含缓存命中），进度条用这个，避免逐层回跳。 */
    val overallDownloadedBytes: Long = 0,
    /** 全部层总字节；未知（manifest 未解析）时为 0。 */
    val overallTotalBytes: Long = 0,
    val mirrorHost: String? = null,
    val message: String? = null,
)
