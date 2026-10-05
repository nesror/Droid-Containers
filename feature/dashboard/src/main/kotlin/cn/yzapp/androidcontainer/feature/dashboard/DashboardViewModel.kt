package cn.yzapp.androidcontainer.feature.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.data.ContainerRuntime
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val runningCount: Int = 0,
    val totalCount: Int = 0,
    val imageCount: Int = 0,
    val storageUsedBytes: Long = 0,
    /** 最近创建的容器（最多 5 条）。 */
    val containers: List<ContainerEntity> = emptyList(),
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val containerRepository = DataGraph.containerRepository
    private val imageRepository = DataGraph.imageRepository

    val uiState: StateFlow<DashboardUiState> =
        combine(
            containerRepository.observeContainers(),
            imageRepository.inventory,
            containerRepository.runtimeStates,
        ) { containers, images, runtimes ->
            DashboardUiState(
                runningCount = runtimes.count { it.value == ContainerRuntime.RUNNING },
                totalCount = containers.size,
                imageCount = images.size,
                storageUsedBytes = images.sumOf { it.sizeBytes },
                containers = containers.take(5),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())
}
