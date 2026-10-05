package cn.yzapp.androidcontainer.core.engine.tar

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException

/**
 * 层压缩格式。由 manifest 的 layer.mediaType 推导，
 * estargz / zstd-chunked / 厂商扩展一律返回 null 拒绝（方案 §3.2/§3.3），
 * 绝不按错误格式解出一份"看似成功实则损坏"的 rootfs。
 */
enum class LayerFormat { TAR, GZIP, ZSTD }

fun layerFormatFromMediaType(mediaType: String): LayerFormat? =
    when {
        mediaType.endsWith("tar+gzip") || mediaType.endsWith("tar.gzip") ||
            mediaType == "application/x-gzip" -> LayerFormat.GZIP
        mediaType.endsWith("tar+zstd") || mediaType.endsWith("tar.zstd") -> LayerFormat.ZSTD
        mediaType.endsWith("/tar") || mediaType.endsWith(".tar") -> LayerFormat.TAR
        else -> null
    }

internal fun layerFormatError(mediaType: String): EngineException =
    EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "Unsupported layer mediaType: $mediaType")
