package cn.yzapp.androidcontainer.feature.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.common.LanAddress
import cn.yzapp.androidcontainer.core.common.RemoteControlActions
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.ContainerRuntime
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.data.RemoteAuditLog
import cn.yzapp.androidcontainer.core.data.SettingsRepository
import cn.yzapp.androidcontainer.feature.settings.keepalive.VendorBgPolicy
import cn.yzapp.androidcontainer.feature.settings.keepalive.batteryWhitelistIntent
import cn.yzapp.androidcontainer.feature.settings.keepalive.detectVendorPolicy
import cn.yzapp.androidcontainer.feature.settings.keepalive.isIgnoringBatteryOptimizations
import cn.yzapp.androidcontainer.feature.settings.keepalive.openVendorBackgroundSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class RemoteAuditEntry(val timeMs: Long, val text: String, val success: Boolean)

/** 镜像源测速结果（设置页「测速并排序」）。 */
data class MirrorProbeEntry(val host: String, val latencyMs: Long?)

data class SettingsUiState(
    val mirrorsInput: String = "",
    val dnsInput: String = "",
    val autostart: Boolean = true,
    val saved: Boolean = false,
    /** 保存（镜像源/DNS）专属错误，展示在保存按钮旁；远程控制等错误走 [error]。 */
    val saveError: String? = null,
    val error: String? = null,
    /** 用户已编辑未保存，避免设置流覆盖输入。 */
    val touched: Boolean = false,

    // ---- 远程控制（M8） ----
    val webEnabled: Boolean = false,
    val webPortInput: String = SettingsRepository.DEFAULT_WEB_PORT.toString(),
    val dockerEnabled: Boolean = false,
    val dockerPortInput: String = SettingsRepository.DEFAULT_DOCKER_PORT.toString(),
    val dockerLanEnabled: Boolean = false,
    val apiToken: String? = null,
    val lanAddress: String? = null,
    val tokenCopied: Boolean = false,
    val audit: List<RemoteAuditEntry>? = null,

    // ---- 问题反馈（日志导出） ----
    val exporting: Boolean = false,
    /** 导出成功待分享的文件；UI 拉起分享面板后置空。 */
    val pendingShare: File? = null,

    // ---- 后台运行保活 ----
    val vendor: VendorBgPolicy = VendorBgPolicy.OTHER,
    /** 已加入电池优化白名单。 */
    val batteryIgnored: Boolean = false,
    /** 运行中容器数（保活前台服务的工作状态）。 */
    val runningContainers: Int = 0,

    // ---- 镜像源测速 ----
    /** 正在并发探测各源 /v2/ 端点。 */
    val probing: Boolean = false,
    /** 最近一次测速结果（host → RTT；null = 不可达）。 */
    val probeResults: List<MirrorProbeEntry> = emptyList(),
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "Settings"
    }

    private val repository = DataGraph.settingsRepository

    /**
     * 远程控制门禁依据（m8_m9 方案 §7）：**仅 `Unlocked` 允许开启 Web 控制台**；
     * `Unknown`（首次查询尚未返回）也按未解锁处理——开关置灰、服务不启动，避免留下可被局域网访问的端口。
     */
    val entitlementState: StateFlow<EntitlementState> = DataGraph.entitlementRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntitlementState.Unknown)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // 进入设置页顺带做一次节流刷新，让解锁状态尽快从 Unknown 落到确定值
        viewModelScope.launch { DataGraph.entitlementRepository.refresh(force = false) }
        viewModelScope.launch {
            combine(repository.mirrors, repository.dns, repository.autostartEnabled) { m, d, a ->
                Triple(m, d, a)
            }.collect { (m, d, a) ->
                val current = _uiState.value
                _uiState.value = current.copy(
                    mirrorsInput = if (current.touched) current.mirrorsInput else m.joinToString("\n"),
                    dnsInput = if (current.touched) current.dnsInput else d,
                    autostart = a,
                )
            }
        }
        viewModelScope.launch {
            combine(
                repository.webEnabled,
                repository.webPort,
                repository.dockerEnabled,
                repository.dockerPort,
                repository.dockerLanEnabled,
            ) { w, wp, d, dp, dlan -> Remote4(w, wp, d, dp, dlan) }
                .combine(repository.apiToken) { r, t -> r to t }
                .collect { (r, token) ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        webEnabled = r.webEnabled,
                        webPortInput = if (current.webEnabled != r.webEnabled || current.webPortInput.toIntOrNull() == null) {
                            r.webPort.toString()
                        } else {
                            current.webPortInput
                        },
                        dockerEnabled = r.dockerEnabled,
                        dockerPortInput = if (current.dockerEnabled != r.dockerEnabled || current.dockerPortInput.toIntOrNull() == null) {
                            r.dockerPort.toString()
                        } else {
                            current.dockerPortInput
                        },
                        dockerLanEnabled = r.dockerLanEnabled,
                        apiToken = token,
                    )
                }
        }
        _uiState.value = _uiState.value.copy(lanAddress = LanAddress.firstIpv4())
        viewModelScope.launch {
            DataGraph.containerRepository.runtimeStates.collect { states ->
                _uiState.value = _uiState.value.copy(
                    runningContainers = states.values.count { it == ContainerRuntime.RUNNING },
                )
            }
        }
        refreshKeepAliveState()
    }

    // ---- 后台运行保活 ----

    /** 刷新电池白名单 / 厂商标识（进入页面与拉起系统页返回后调用）。 */
    fun refreshKeepAliveState() {
        _uiState.value = _uiState.value.copy(
            vendor = detectVendorPolicy(),
            batteryIgnored = isIgnoringBatteryOptimizations(getApplication()),
        )
    }

    /** 拉起系统"忽略电池优化"确认框（用户手动确认，非静默授权）。 */
    fun requestBatteryWhitelist() {
        try {
            getApplication<Application>().startActivity(
                batteryWhitelistIntent(getApplication()),
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "battery whitelist request failed", e)
        }
    }

    /** 打开厂商自启/后台设置（候选组件逐个尝试，失败回退应用详情页）。 */
    fun openVendorSettings() {
        openVendorBackgroundSettings(getApplication(), _uiState.value.vendor)
    }

    private data class Remote4(
        val webEnabled: Boolean,
        val webPort: Int,
        val dockerEnabled: Boolean,
        val dockerPort: Int,
        val dockerLanEnabled: Boolean,
    )

    // ---- 基础设置 ----

    fun onMirrorsChange(value: String) {
        _uiState.value = _uiState.value.copy(mirrorsInput = value, touched = true, saved = false, saveError = null)
    }

    /**
     * 镜像源测速并排序：并发探测 mirrorsInput 里每个源的 /v2/ RTT，
     * 按「快 → 慢 → 不可达」重排输入框（拉取引擎按列表顺序逐源回退，排前 = 优先用），
     * 结果展示在按钮下方。改动需要用户再点「保存」落库——保持与手改一致的显式保存语义。
     */
    fun probeAndSortMirrors() {
        if (_uiState.value.probing) return
        val hosts = _uiState.value.mirrorsInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(probing = true, probeResults = emptyList(), saveError = null)
            try {
                val results = DataGraph.imageRepository.probeMirrors(hosts).map {
                    MirrorProbeEntry(host = it.host, latencyMs = it.latencyMs)
                }
                val sorted = results.sortedWith(
                    compareBy({ it.latencyMs ?: Long.MAX_VALUE }, { hosts.indexOf(it.host) }),
                )
                _uiState.value = _uiState.value.copy(
                    probing = false,
                    probeResults = sorted,
                    mirrorsInput = sorted.joinToString("\n") { it.host },
                    touched = true,
                    saved = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(probing = false, saveError = e.message)
            }
        }
    }

    fun onDnsChange(value: String) {
        _uiState.value = _uiState.value.copy(dnsInput = value, touched = true, saved = false, saveError = null)
    }

    fun onAutostartChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autostart = enabled)
        viewModelScope.launch { repository.setAutostart(enabled) }
    }

    fun save() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val mirrors = state.mirrorsInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (mirrors.isEmpty() || state.dnsInput.isBlank()) {
                    _uiState.value = state.copy(
                        saveError = getApplication<Application>().getString(R.string.settings_save_empty),
                    )
                    return@launch
                }
                repository.setMirrors(mirrors)
                repository.setDns(state.dnsInput)
                _uiState.value = _uiState.value.copy(saved = true, saveError = null, touched = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(saveError = e.message)
            }
        }
    }

    // ---- 远程控制（M8） ----

    fun onWebEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (enabled) repository.ensureApiToken()
                repository.setWebEnabled(enabled)
                syncService()
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                AppLogger.w(TAG, "toggle web console failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun onWebPortChange(value: String) {
        _uiState.value = _uiState.value.copy(webPortInput = value, error = null)
        val port = value.toIntOrNull()
        if (port == null || port !in SettingsRepository.MIN_PORT..SettingsRepository.MAX_PORT) {
            _uiState.value = _uiState.value.copy(error = getApplication<Application>()
                .getString(R.string.settings_remote_port_invalid))
            return
        }
        viewModelScope.launch { repository.setWebPort(port) }
    }

    fun onDockerEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setDockerEnabled(enabled)
                syncService()
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                AppLogger.w(TAG, "toggle docker api failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun onDockerLanChange(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setDockerLanEnabled(enabled)
                // 绑定地址变化需要重建引擎；startServer 内部先 stopAll 再建，直接重发即可
                syncService()
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                AppLogger.w(TAG, "toggle docker lan failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun onDockerPortChange(value: String) {
        _uiState.value = _uiState.value.copy(dockerPortInput = value, error = null)
        val port = value.toIntOrNull()
        if (port == null || port !in SettingsRepository.MIN_PORT..SettingsRepository.MAX_PORT) {
            _uiState.value = _uiState.value.copy(error = getApplication<Application>()
                .getString(R.string.settings_remote_port_invalid))
            return
        }
        viewModelScope.launch { repository.setDockerPort(port) }
    }

    fun onTokenReset() {
        viewModelScope.launch {
            repository.resetApiToken()
            // 旧 token 在服务内存中仍有效 → 重启服务使新 token 生效
            syncService(restart = true)
        }
    }

    fun onTokenCopied() {
        _uiState.value = _uiState.value.copy(tokenCopied = true)
    }

    fun loadAudit() {
        viewModelScope.launch {
            val entries = RemoteAuditLog.recent().map { entry ->
                RemoteAuditEntry(
                    timeMs = entry.timeMs,
                    text = "${entry.source}/${entry.action}: ${entry.detail}",
                    success = entry.success,
                )
            }
            _uiState.value = _uiState.value.copy(audit = entries)
        }
    }

    fun dismissAudit() {
        _uiState.value = _uiState.value.copy(audit = null)
    }

    // ---- 问题反馈（日志导出） ----

    /** 导出日志到 cache/exports 并交由 UI 拉起系统分享面板。 */
    fun exportLogs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(exporting = true, error = null)
            try {
                val exportsDir = File(getApplication<Application>().cacheDir, "exports")
                val file = AppLogger.exportTo(exportsDir)
                if (file == null) {
                    _uiState.value = _uiState.value.copy(
                        exporting = false,
                        error = getApplication<Application>().getString(R.string.settings_export_empty),
                    )
                } else {
                    AppLogger.i(TAG, "logs exported: ${file.name} (${file.length()} bytes)")
                    _uiState.value = _uiState.value.copy(exporting = false, pendingShare = file)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "log export failed", e)
                _uiState.value = _uiState.value.copy(exporting = false, error = e.message)
            }
        }
    }

    fun onShareHandled() {
        _uiState.value = _uiState.value.copy(pendingShare = null)
    }

    /** 任一开关变化后同步前台服务：两者都关 → 停服务；否则启（restart 时先停再启）。 */
    private suspend fun syncService(restart: Boolean = false) {
        val context: Context = getApplication()
        val webEnabled = repository.webEnabled.first()
        val dockerEnabled = repository.dockerEnabled.first()
        if (restart) stopServer(context)
        if (webEnabled || dockerEnabled) {
            startServer(context)
        } else {
            stopServer(context)
        }
    }

    private fun startServer(context: Context) {
        context.startForegroundService(
            Intent(RemoteControlActions.ACTION_START).setPackage(context.packageName),
        )
    }

    private fun stopServer(context: Context) {
        context.startService(
            Intent(RemoteControlActions.ACTION_STOP).setPackage(context.packageName),
        )
    }
}
