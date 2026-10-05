package cn.yzapp.androidcontainer.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 应用设置（方案 §5 设置页）：镜像源列表（每行一个）、DNS、自启策略；
 * M8 起新增远程控制：Web 总开关/端口、Docker 兼容独立开关/端口、Bearer token。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val MIRRORS = stringPreferencesKey("mirrors")
        val DNS = stringPreferencesKey("dns")
        val AUTOSTART = stringPreferencesKey("autostart")
        val ONBOARDING_DONE = stringPreferencesKey("onboarding_done")
        val WEB_ENABLED = stringPreferencesKey("web_enabled")
        val WEB_PORT = intPreferencesKey("web_port")
        val DOCKER_ENABLED = stringPreferencesKey("docker_enabled")
        val DOCKER_PORT = intPreferencesKey("docker_port")
        val DOCKER_LAN_ENABLED = stringPreferencesKey("docker_lan_enabled")
        val API_TOKEN = stringPreferencesKey("api_token")
    }

    /** 镜像源（每行一个）；空则回退默认列表。 */
    val mirrors: Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[Keys.MIRRORS]
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
        if (raw.isEmpty()) ImageRepository.DEFAULT_MIRRORS else raw
    }

    /** 容器内 DNS（写入 /etc/resolv.conf 绑定）。 */
    val dns: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DNS]?.trim().takeIf { !it.isNullOrEmpty() } ?: DEFAULT_DNS
    }

    /** App 启动时自动拉起 autoStart 容器。 */
    val autostartEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.AUTOSTART]?.toBooleanStrictOrNull() ?: true
    }

    suspend fun setMirrors(list: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MIRRORS] = list.joinToString("\n")
        }
    }

    suspend fun setDns(value: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DNS] = value.trim()
        }
    }

    suspend fun setAutostart(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AUTOSTART] = enabled.toString()
        }
    }

    // ------------------------------------------------------------ 后台运行引导（首次启动）

    /** 首次启动后台保活引导是否已完成（跳过也视为完成，设置页可重新查看）。 */
    val onboardingCompleted: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_DONE]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun setOnboardingCompleted() {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_DONE] = "true"
        }
    }

    // ------------------------------------------------------------ 远程控制（M8/M9）

    /** Web 控制台总开关：默认关闭（安全模型，m8_m9 §2.2）。 */
    val webEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.WEB_ENABLED]?.toBooleanStrictOrNull() ?: false
    }

    /** Web/API 端口。 */
    val webPort: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.WEB_PORT]?.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_WEB_PORT
    }

    /** Docker Engine API 独立开关（与 Web 分离，无 header 鉴权，默认关闭）。 */
    val dockerEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DOCKER_ENABLED]?.toBooleanStrictOrNull() ?: false
    }

    /** Docker 兼容端口（2375 是特权端口，Android 非 root 不可绑，用 23760）。 */
    val dockerPort: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DOCKER_PORT]?.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_DOCKER_PORT
    }

    /**
     * Docker 实例局域网暴露开关（默认关闭，审查 P0-1 修法①）：
     * 关 = 只绑 127.0.0.1（adb forward / 手机本机可用）；开 = 绑 0.0.0.0，
     * 同网段任意主机可无鉴权完全控制容器——UI 侧必须二次确认后才允许置位。
     */
    val dockerLanEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DOCKER_LAN_ENABLED]?.toBooleanStrictOrNull() ?: false
    }

    /** Bearer token（首次开启时惰性生成 32 hex；为 null 表示尚未生成）。 */
    val apiToken: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.API_TOKEN]?.takeIf { it.isNotBlank() }
    }

    /** 读取现有 token，没有则生成（首次开启 Web 时调用）。 */
    suspend fun ensureApiToken(): String {
        apiToken.first()?.let { return it }
        val token = randomToken()
        context.settingsDataStore.edit { prefs ->
            if (prefs[Keys.API_TOKEN].isNullOrBlank()) prefs[Keys.API_TOKEN] = token
        }
        return apiToken.first() ?: token
    }

    /** 重置 token（旧 token 立即失效）。 */
    suspend fun resetApiToken(): String {
        val token = randomToken()
        context.settingsDataStore.edit { prefs -> prefs[Keys.API_TOKEN] = token }
        return token
    }

    suspend fun setWebEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.WEB_ENABLED] = enabled.toString() }
    }

    suspend fun setWebPort(port: Int) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.WEB_PORT] = port }
    }

    suspend fun setDockerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.DOCKER_ENABLED] = enabled.toString() }
    }

    suspend fun setDockerPort(port: Int) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.DOCKER_PORT] = port }
    }

    suspend fun setDockerLanEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.DOCKER_LAN_ENABLED] = enabled.toString() }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_DNS = "1.1.1.1"
        const val DEFAULT_WEB_PORT = 8765
        const val DEFAULT_DOCKER_PORT = 23760
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
