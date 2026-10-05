package cn.yzapp.androidcontainer.feature.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.feature.settings.keepalive.VendorBgPolicy
import cn.yzapp.androidcontainer.feature.settings.keepalive.batteryWhitelistIntent
import cn.yzapp.androidcontainer.feature.settings.keepalive.detectVendorPolicy
import cn.yzapp.androidcontainer.feature.settings.keepalive.isIgnoringBatteryOptimizations
import cn.yzapp.androidcontainer.feature.settings.keepalive.openVendorBackgroundSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val vendor: VendorBgPolicy = VendorBgPolicy.OTHER,
    /** 已加入电池优化白名单。 */
    val batteryIgnored: Boolean = false,
    /** 已授予通知权限（API < 33 恒为 true）。 */
    val notificationsGranted: Boolean = false,
)

/**
 * 首次启动后台保活引导（方案 §5）：通知权限 → 电池优化白名单 → 厂商自启设置。
 * 完成或跳过都会写入 onboardingCompleted（设置页可重新查看）。
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val context: Context = getApplication()
        _uiState.value = _uiState.value.copy(
            vendor = detectVendorPolicy(),
            batteryIgnored = isIgnoringBatteryOptimizations(context),
            notificationsGranted = isNotificationsGranted(context),
        )
    }

    /** 拉起系统"忽略电池优化"确认框（用户手动确认，非静默授权）。 */
    fun requestBatteryWhitelist() {
        try {
            getApplication<Application>().startActivity(batteryWhitelistIntent(getApplication()))
        } catch (e: Exception) {
            AppLogger.w(TAG, "battery whitelist request failed", e)
        }
        // 返回后 onResume 由 UI 触发 refresh() 校正状态
    }

    fun openVendorSettings() {
        openVendorBackgroundSettings(getApplication(), _uiState.value.vendor)
    }

    /** 通知权限申请结果回调（Compose launcher）。 */
    fun onNotificationResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(notificationsGranted = granted)
    }

    /** 完成引导（跳过同样视为完成，避免反复打扰；设置页可重新查看）。 */
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                DataGraph.settingsRepository.setOnboardingCompleted()
            } catch (e: Exception) {
                AppLogger.w(TAG, "save onboarding flag failed", e)
            }
            onDone()
        }
    }

    private fun isNotificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "Onboarding"
    }
}
