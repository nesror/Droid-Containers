package cn.yzapp.androidcontainer.core.model

/**
 * 引擎错误码（方案 §6），retryable 决定 UI 是否展示重试按钮。
 */
enum class EngineErrorCode(val retryable: Boolean) {
    UNSUPPORTED_DEVICE(false),
    RUNTIME_MISSING(true),
    DISK_FULL(false),
    MIRROR_UNAVAILABLE(true),
    MANIFEST_UNSUPPORTED(false),
    EXTRACT_FAILED(true),
    START_FAILED(true),
    PORT_CONFLICT(false),
    HEALTH_TIMEOUT(true),
    CANCELLED(false),

    /** compose：服务镜像尚未拉取（阶段一不自动拉取，仅提示，方案 §3.1）。 */
    IMAGE_MISSING(false),

    /** compose：YAML 非法/语义错误（message 带行号）。 */
    COMPOSE_INVALID(false),

    /**
     * 镜像正被运行中的容器使用：同 tag 重拉会先清空旧 rootfs，删除会整目录删 layers/<image>，
     * 两者都会让运行中的进程立刻 ENOENT（对齐 docker 的 "image is being used by running container"）。
     */
    IMAGE_IN_USE(false),
}

class EngineException(
    val code: EngineErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
