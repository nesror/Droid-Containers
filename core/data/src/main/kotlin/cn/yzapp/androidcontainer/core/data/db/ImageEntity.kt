package cn.yzapp.androidcontainer.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 镜像库存表（方案 §4 images）。 */
@Entity(tableName = "images")
data class ImageEntity(
    /** 用户输入的引用（如 alpine:3.20），主键。 */
    @PrimaryKey val ref: String,
    val repo: String,
    val tag: String,
    val manifestDigest: String,
    val rootfsPath: String,
    val sizeBytes: Long,
    val createdAt: Long,
)
