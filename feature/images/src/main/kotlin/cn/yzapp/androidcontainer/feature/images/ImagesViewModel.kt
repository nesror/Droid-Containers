package cn.yzapp.androidcontainer.feature.images

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yzapp.androidcontainer.core.data.AppLogger
import cn.yzapp.androidcontainer.core.data.DataGraph
import cn.yzapp.androidcontainer.core.model.PullStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val ACTION_PULL = "cn.yzapp.androidcontainer.action.PULL"
const val EXTRA_REF = "extra_ref"

data class ImagesUiState(
    val input: String = "alpine:3.20",
    /** 正在导入 docker save tar（SAF 选定后到解压完成）。 */
    val importing: Boolean = false,
    /** 导入成功提示（ref）。 */
    val importMessage: String? = null,
    /** 导入失败错误。 */
    val importError: String? = null,
)

class ImagesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DataGraph.imageRepository

    private val _uiState = MutableStateFlow(ImagesUiState())
    val uiState: StateFlow<ImagesUiState> = _uiState.asStateFlow()

    private val _input = MutableStateFlow("alpine:3.20")
    val input: StateFlow<String> = _input.asStateFlow()

    val inventory =
        repository.inventory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 拉取进度（ref → progress），直接暴露仓库 StateFlow；UI 单独 collect（P1-12）。 */
    val pullStates: StateFlow<Map<String, cn.yzapp.androidcontainer.core.model.PullProgress>> =
        repository.pullStates

    fun onInputChange(value: String) {
        _input.value = value
    }

    /** 经 dataSync 前台服务触发拉取（隐式 action，包名限定）。 */
    fun pull() {
        val ref = _input.value.trim()
        if (ref.isEmpty()) return
        val intent = Intent(ACTION_PULL).setPackage(getApplication<Application>().packageName)
            .putExtra(EXTRA_REF, ref)
        getApplication<Application>().startForegroundService(intent)
    }

    fun remove(ref: String) {
        viewModelScope.launch { repository.remove(ref) }
    }

    /**
     * 导入 `docker save` 导出的 tar：SAF 流先落 cache 临时文件
     * （manifest.json 可能晚于层条目出现，需要二次顺序扫描，流无法回退），
     * 再交引擎双遍解压。完成后临时文件即删。
     */
    fun importTar(uri: Uri) {
        if (_uiState.value.importing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importing = true, importMessage = null, importError = null)
            val tmp = File(getApplication<Application>().cacheDir, "import/docker-load-${System.currentTimeMillis()}.tar")
            try {
                tmp.parentFile?.mkdirs()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("cannot open $uri")
                }
                if (tmp.length() == 0L) {
                    _uiState.value = _uiState.value.copy(
                        importing = false,
                        importError = getApplication<Application>().getString(R.string.images_import_failed_empty),
                    )
                    return@launch
                }
                val entity = repository.importTar(tmp) { msg -> AppLogger.i("Images", "import: $msg") }
                _uiState.value = _uiState.value.copy(
                    importing = false,
                    importMessage = getApplication<Application>().getString(R.string.images_import_success, entity.ref),
                )
            } catch (e: Exception) {
                AppLogger.w("Images", "import tar failed", e)
                _uiState.value = _uiState.value.copy(
                    importing = false,
                    importError = getApplication<Application>()
                        .getString(R.string.images_import_failed, e.message ?: ""),
                )
            } finally {
                tmp.delete()
            }
        }
    }

    companion object {
        fun isActiveStage(stage: PullStage): Boolean =
            stage !in setOf(PullStage.READY, PullStage.FAILED, PullStage.CANCELLED)
    }
}
