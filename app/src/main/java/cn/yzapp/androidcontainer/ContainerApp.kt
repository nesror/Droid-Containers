package cn.yzapp.androidcontainer

import android.app.Application
import android.content.Intent
import cn.yzapp.androidcontainer.core.common.ContainerRuntimeActions
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.engine.compose.TemplateCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ContainerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DataGraph.init(this)
        // 自启策略（方案 §5）：先校正僵尸 RUNNING，再拉起 autoStart 容器，
        // 有容器运行时同步拉起保活前台服务（防止退后台被查杀）。
        appScope.launch {
            try {
                DataGraph.containerRepository.reconcile()
                // 孤儿 rootfs 清理（审查 P1-14 兜底）：库存缺失的 layers/* 目录按百 MB 计，
                // 不清理则 UI 永远无法回收
                DataGraph.imageRepository.cleanupOrphanLayers()
                DataGraph.containerRepository.autoStartAll()
                if (DataGraph.containerRepository.runtimeStates.value.containsValue(
                        cn.yzapp.androidcontainer.core.data.ContainerRuntime.RUNNING,
                    )
                ) {
                    startRuntimeService()
                }
            } catch (e: Exception) {
                // 自启失败不阻塞应用
                AppLogger.w("App", "autostart reconcile failed", e)
            }
        }
        // 远程控制按开关自恢复（m8_m9 验收 #10）
        appScope.launch {
            try {
                val settings = DataGraph.settingsRepository
                val web = settings.webEnabled.first()
                val docker = settings.dockerEnabled.first()
                AppLogger.i("App", "remote restore check web=$web docker=$docker")
                if (web || docker) {
                    ServerService.start(this@ContainerApp)
                }
            } catch (e: Exception) {
                // 服务拉起失败不阻塞应用
                AppLogger.w("App", "remote service restore failed", e)
            }
        }
        // 模板包权益：启动时做一次静默恢复查询（同时补做遗漏的 acknowledge）
        appScope.launch {
            try {
                DataGraph.entitlementRepository.refresh(force = false)
            } catch (e: Exception) {
                // 无 Play 服务或断网时静默跳过，不改写本地权益位
                AppLogger.w("App", "entitlement sync failed", e)
            }
        }
        // 模板目录：后台预热。模板内容现在是 YAML 数据，解析一次全进程复用，
        // 预热后进入模板库就不必在主线程解析；失败也不影响启动（使用时按需重试）。
        appScope.launch {
            try {
                TemplateCatalog.warmUp()
            } catch (e: Exception) {
                AppLogger.w("App", "template catalog warm-up failed", e)
            }
        }
    }

    /** 容器运行中 → 启动保活前台服务；启动失败仅记日志（部分 ROM 限制后台拉起）。 */
    private fun startRuntimeService() {
        try {
            val intent = Intent(ContainerRuntimeActions.ACTION_START).setPackage(packageName)
            startForegroundService(intent)
        } catch (e: Exception) {
            AppLogger.w("App", "runtime keepalive service start failed", e)
        }
    }
}
