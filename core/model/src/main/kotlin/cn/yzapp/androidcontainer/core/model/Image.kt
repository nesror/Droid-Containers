package cn.yzapp.androidcontainer.core.model

/**
 * 已拉取镜像的元数据。rootfs / 层缓存等大文件不入库，只存路径与 digest（方案 §4）。
 */
data class Image(
    val ref: String,
    val digest: String,
    val sizeBytes: Long,
    val platform: String,
    val createdAt: Long,
)
