package cn.yzapp.androidcontainer.core.data

import android.content.Context
import cn.yzapp.androidcontainer.core.billing.BillingGraph
import cn.yzapp.androidcontainer.core.billing.EntitlementRepository
import cn.yzapp.androidcontainer.core.data.db.InventoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 轻量服务定位器：app 启动时初始化，供 app 服务与 feature ViewModel 共享同一实例。
 * Room 单实例；两个仓库共享同一 dao。后续引入 Hilt 时整体替换（方案 §4）。
 */
object DataGraph {

    /**
     * 应用级协程作用域（审查 P1-11）：镜像拉取 / 容器启停 / 编排 up 这类**分钟级长任务**
     * 必须挂这里，不能挂 `viewModelScope`。
     *
     * 导航改为「条目出栈即清 ViewModelStore」后，页面 ViewModel 会随导航条目销毁
     * （切 tab 就会销毁），挂在它上面的任务被静默取消——表现为「拉了一半的镜像没了」
     * 「点了启动但容器永远起不来」。该 scope 与进程同生命周期，任务自身结束即释放，
     * 不持有任何 UI 引用；失败经 [OpsErrors] 抛给可见页面。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var _imageRepository: ImageRepository? = null

    @Volatile
    private var _containerRepository: ContainerRepository? = null

    @Volatile
    private var _settingsRepository: SettingsRepository? = null

    @Volatile
    private var _composeRepository: ComposeRepository? = null

    @Volatile
    private var _entitlementRepository: EntitlementRepository? = null

    @Volatile
    private var _updateChecker: UpdateChecker? = null

    val imageRepository: ImageRepository
        get() = _imageRepository
            ?: throw IllegalStateException("DataGraph not initialized; call init() in Application.onCreate")

    val containerRepository: ContainerRepository
        get() = _containerRepository
            ?: throw IllegalStateException("DataGraph not initialized; call init() in Application.onCreate")

    val settingsRepository: SettingsRepository
        get() = _settingsRepository
            ?: throw IllegalStateException("DataGraph not initialized; call init() in Application.onCreate")

    val composeRepository: ComposeRepository
        get() = _composeRepository
            ?: throw IllegalStateException("DataGraph not initialized; call init() in Application.onCreate")

    /** 模板包权益（Google Play 一次性商品）。 */
    val entitlementRepository: EntitlementRepository
        get() = _entitlementRepository
            ?: throw IllegalStateException("DataGraph not initialized; call init() in Application.onCreate")

    /** 应用更新检查（渠道接缝：cn 走 GitHub Releases，global 为空壳、入口不展示）。 */
    val updateChecker: UpdateChecker
        get() = _updateChecker
            ?: throw IllegalStateException("DataGraph not initialized; call init() in Application.onCreate")

    fun init(context: Context) {
        synchronized(this) {
            if (_imageRepository == null) {
                val appContext = context.applicationContext
                AppLogger.init(appContext)
                val dao = InventoryDatabase.create(appContext).inventoryDao()
                val settings = SettingsRepository(appContext)
                val imageRepository = ImageRepository(
                    context = appContext,
                    dao = dao,
                    mirrorsProvider = { settings.mirrors.first() },
                )
                _imageRepository = imageRepository
                val containerRepository = ContainerRepository(
                    context = appContext,
                    dao = dao,
                    engineDir = File(appContext.filesDir, "engine"),
                    settings = settings,
                    images = imageRepository,
                )
                _containerRepository = containerRepository
                _composeRepository = ComposeRepository(
                    dao = dao,
                    containers = containerRepository,
                    images = imageRepository,
                    engineDir = File(appContext.filesDir, "engine"),
                )
                _settingsRepository = settings
                _entitlementRepository = BillingGraph.create(appContext)
                _updateChecker = UpdateCheckerGraph.create(appContext)
                AppLogger.i("DataGraph", "initialized")
            }
        }
    }
}
