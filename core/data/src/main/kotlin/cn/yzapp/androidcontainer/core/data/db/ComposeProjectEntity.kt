package cn.yzapp.androidcontainer.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * compose 项目表（方案 §3.1 阶段一第 1 项）。
 *
 * 只存 YAML 原文，**解析产物不落库**：解析是纯函数且极廉价，按需重解析可避免
 * 「YAML 改了、库里的解析结果没跟上」的不一致。
 */
@Entity(tableName = "compose_projects")
data class ComposeProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val yamlContent: String,
    val createdAt: Long = 0,
)
