package cn.yzapp.androidcontainer.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import cn.yzapp.androidcontainer.core.model.ContainerStatus

/**
 * 容器表（方案 §4 containers）。
 *
 * projectId / serviceName / cmdJson / envJson 为 M7 编排页新增列（DB v2 迁移追加）：
 * - projectId + serviceName 标识**服务身份**，保证 compose up 幂等复用同一容器；
 * - cmdJson / envJson 存 service 的 command / environment；非编排创建的容器为 null（用默认入口）。
 *
 * dockerId 为 M9 Docker 兼容层新增列（DB v3 迁移追加）：64 hex 字符的 Docker 风格 Id，
 * 供 `docker` CLI / Portainer 引用（客户端假定 64 hex、可 12 位前缀引用）；
 * 旧记录首次被 Docker API 访问时惰性生成。
 *
 * restartPolicy 为 restart 策略新增列（DB v4 迁移追加）：compose 服务的 `restart` 字段
 * （no / always / unless-stopped / on-failure）；主进程自然退出后按策略自动拉起
 * （HA 网页端「重启」即此场景）。非编排创建的容器为 null（按 no 处理）。
 *
 * desiredRunning 为进程重启自恢复新增列（DB v5 迁移追加）：持久化的「期望运行」标志，
 * 对齐 docker「daemon 重启后恢复 restart=always/unless-stopped 且之前在跑的容器」语义。
 * start 成功置 true；用户显式 stop 置 false（unless-stopped 不恢复的关键区分）；
 * App 进程被杀时**保持不变**（进程死亡 ≠ 用户停止），autoStartAll 据此拉起。
 */
@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageRef: String,
    val status: ContainerStatus,
    val portsJson: String = "[]",
    val mountsJson: String = "[]",
    val autoStart: Boolean = false,
    val createdAt: Long = 0,
    val projectId: String? = null,
    val serviceName: String? = null,
    val cmdJson: String? = null,
    val envJson: String? = null,
    val dockerId: String? = null,
    val restartPolicy: String? = null,
    val desiredRunning: Boolean = false,
) {

    val isComposeService: Boolean get() = projectId != null && serviceName != null
}
