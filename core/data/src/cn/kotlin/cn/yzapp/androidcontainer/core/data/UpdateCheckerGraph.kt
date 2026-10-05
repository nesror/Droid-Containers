package cn.yzapp.androidcontainer.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 服务定位入口（`cn` 渠道）：与 `global` 同名接缝，`DataGraph` 无需感知渠道差异。
 */
object UpdateCheckerGraph {

    fun create(context: Context): UpdateChecker = GitHubUpdateChecker(context)
}

/**
 * GitHub Releases 检查更新（仅 `cn` 渠道）。
 *
 * 数据源与顺序（前一源失败才尝试下一源）：
 * 1. `api.github.com/repos/nesror/Droid-Containers/releases/latest` —— 含更新日志（body）
 * 2. 同一 API 走 `github.191005.xyz` 反代前缀 —— 国内直连 GitHub API 不稳时的兜底
 * 3. `github.com/.../releases/latest` 302 重定向末段取 tag —— 仅版本号、无更新日志
 *
 * 版本比较见 [VersionNumbers]：`v1.2.3` 语义化数字段比较，忽略预发布后缀
 * （`v1.2.3-rc1` 与 `1.2.3` 视为同版本，不会触发升级提示）。
 */
internal class GitHubUpdateChecker(context: Context) : UpdateChecker {

    private companion object {
        const val REPO = "nesror/Droid-Containers"
        const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val PROXY_PREFIX = "https://github.191005.xyz/"
        const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    }

    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val local = currentBaseVersion()
            ?: return@withContext UpdateCheckResult.Failed("local versionName unavailable")
        val release = fetchViaApi(API_URL)
            ?: fetchViaApi(PROXY_PREFIX + API_URL)
        if (release != null) return@withContext evaluate(release, local)
        fetchViaRedirect(local)
    }

    private fun evaluate(release: ReleaseDto, local: String): UpdateCheckResult {
        if (release.tagName.isBlank()) return UpdateCheckResult.Failed("release has no tag_name")
        val remote = VersionNumbers.parseBase(release.tagName)
        return if (VersionNumbers.isNewer(remote, local)) {
            UpdateCheckResult.UpdateAvailable(
                tagName = release.tagName,
                versionName = remote,
                changelog = release.body.orEmpty(),
                pageUrl = release.htmlUrl.ifBlank { LATEST_PAGE },
            )
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** 成功且可解析时返回 [ReleaseDto]，网络失败或 HTTP 非 2xx 返回 null（静默换下一源）。 */
    private fun fetchViaApi(url: String): ReleaseDto? = runCatching {
        client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            json.decodeFromString<ReleaseDto>(resp.body.string())
        }
    }.getOrNull()

    private fun fetchViaRedirect(local: String): UpdateCheckResult = runCatching {
        client.newCall(request(LATEST_PAGE)).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@runCatching UpdateCheckResult.Failed("HTTP ${resp.code}")
            }
            // OkHttp 自动跟随重定向，最终 URL 末段即 tag（如 .../releases/download/v1.2.3 → tag 页为 .../tag/v1.2.3）
            val finalUrl = resp.request.url
            val tag = finalUrl.encodedPathSegments.lastOrNull()
            if (tag.isNullOrBlank() || tag == "latest") {
                UpdateCheckResult.Failed("could not resolve latest release tag")
            } else {
                evaluate(ReleaseDto(tagName = tag, htmlUrl = finalUrl.toString()), local)
            }
        }
    }.getOrElse {
        UpdateCheckResult.Failed(it.message ?: it.javaClass.simpleName)
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "DroidContainers/${currentBaseVersion().orEmpty()}")
        .get()
        .build()

    /** PackageManager 的 versionName（cn 构建为 `1.2.3-cn`），取 `-` 前的数字基线。 */
    private fun currentBaseVersion(): String? = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.versionName?.substringBefore('-')
    }.getOrNull()

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
    )
}

/** 语义化版本（`v1.2.3`）数字段比较工具，仅 cn 检查更新使用。 */
internal object VersionNumbers {

    /** `v1.2.3-beta.1` → `1.2.3`：去前导 v/V、去首个 `-` 起的预发布后缀。 */
    fun parseBase(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-')

    fun isNewer(remoteBase: String, localBase: String): Boolean =
        compare(remoteBase, localBase) > 0

    /** 数字段比较（缺失段补 0）：`1.2.10 > 1.2.9`，`1.2 == 1.2.0`。非数字段按 0 处理。 */
    fun compare(a: String, b: String): Int {
        val left = a.split('.').map { segment -> segment.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { segment -> segment.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
