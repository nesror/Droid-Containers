package cn.yzapp.androidcontainer.core.engine.oci

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException

/**
 * 镜像引用解析：`alpine` / `user/repo:tag` / `registry.example.com:5000/repo:tag`。
 * 无 tag 默认 latest；无 registry 默认 Docker Hub（镜像源侧补全 library/ 前缀）。
 */
data class ImageRef(
    val host: String?,
    val repo: String,
    val tag: String,
) {
    /** 镜像源/上游 API 的 repository 路径（Docker Hub 官方镜像补 library/ 前缀）。 */
    val apiRepo: String
        get() = if (host == null && !repo.contains('/')) "library/$repo" else repo

    companion object {
        fun parse(raw: String): ImageRef {
            if (raw.isBlank()) {
                throw EngineException(EngineErrorCode.MANIFEST_UNSUPPORTED, "Empty image reference")
            }
            var rest = raw
            var host: String? = null
            val firstSlash = rest.indexOf('/')
            if (firstSlash > 0) {
                val candidate = rest.substring(0, firstSlash)
                val looksLikeHost =
                    candidate.contains('.') || candidate.contains(':') || candidate == "localhost"
                if (looksLikeHost) {
                    host = candidate
                    rest = rest.substring(firstSlash + 1)
                }
            }
            val tagPart = rest.substringAfterLast(':', "")
            val repo = if (tagPart.isNotEmpty() && !tagPart.contains('/')) {
                rest.removeSuffix(":$tagPart")
            } else {
                rest
            }
            return ImageRef(host, repo, tagPart.ifEmpty { "latest" })
        }
    }
}
