package cn.yzapp.androidcontainer.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.feature.settings.keepalive.VendorBgPolicy
import kotlinx.coroutines.launch

/**
 * 首次启动后台保活引导（方案 §5）——M3 弹窗式分页流：
 * ModalBottomSheet + HorizontalPager，可左右滑动翻页；右上角关闭（视为跳过）；
 * 右下角箭头进入下一步（末页变为完成）；通知/电池授权成功后自动翻页。
 * 完成或关闭均写入 onboardingCompleted，设置页可重新查看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })

    // 通知权限（API 33+ 运行时申请；低版本恒已授权）
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onNotificationResult(granted) }

    // 每次打开时刷新系统状态（电池白名单可能在系统页确认后已变更）
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 授权完成自动进入下一步（仅当正停留在该步骤页时）
    LaunchedEffect(state.notificationsGranted) {
        if (state.notificationsGranted && pagerState.currentPage == PAGE_NOTIFICATIONS) {
            pagerState.animateScrollToPage(PAGE_BATTERY)
        }
    }
    LaunchedEffect(state.batteryIgnored) {
        if (state.batteryIgnored && pagerState.currentPage == PAGE_BATTERY) {
            pagerState.animateScrollToPage(PAGE_VENDOR)
        }
    }

    // 右上角关闭 / 末页完成：先播放收起动画，再写标记并回调
    fun closeAndComplete() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            viewModel.complete(onFinish)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.complete(onFinish) },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            // 顶栏：当前步骤标题 + 右上角关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(pageTitle(pagerState.currentPage)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::closeAndComplete) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }

            // 分页内容：左右滑动翻页
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                OnboardingPage(
                    page = page,
                    state = state,
                    viewModel = viewModel,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }

            // 底部：页码指示点 + 右下角前进/完成按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(PAGE_COUNT) { index ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(width = if (selected) 20.dp else 8.dp, height = 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        )
                    }
                }
                val isLast = pagerState.currentPage == PAGE_COUNT - 1
                FilledIconButton(onClick = {
                    if (isLast) {
                        closeAndComplete()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }) {
                    Icon(
                        imageVector = if (isLast) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(
                            if (isLast) R.string.onboarding_done else R.string.onboarding_next,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    page: Int,
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onRequestNotifications: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            PAGE_INTRO -> {
                Text(
                    text = stringResource(R.string.onboarding_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BulletLine(stringResource(R.string.onboarding_step_notifications))
                        BulletLine(stringResource(R.string.onboarding_step_battery))
                        BulletLine(stringResource(R.string.onboarding_step_vendor))
                    }
                }
            }

            PAGE_NOTIFICATIONS -> {
                Text(
                    text = stringResource(R.string.onboarding_step_notifications_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusOrAction(
                    done = state.notificationsGranted,
                    actionLabel = stringResource(R.string.onboarding_action_allow),
                    onAction = onRequestNotifications,
                )
            }

            PAGE_BATTERY -> {
                Text(
                    text = stringResource(R.string.onboarding_step_battery_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusOrAction(
                    done = state.batteryIgnored,
                    actionLabel = stringResource(R.string.onboarding_action_request_battery),
                    onAction = viewModel::requestBatteryWhitelist,
                )
            }

            PAGE_VENDOR -> {
                Text(
                    text = stringResource(R.string.onboarding_step_vendor_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::openVendorSettings) {
                    Text(stringResource(R.string.onboarding_action_open_vendor))
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(vendorNameRes(state.vendor)),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(vendorStepsRes(state.vendor)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            PAGE_READY -> {
                Text(
                    text = stringResource(R.string.onboarding_ready_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.keepalive_note_common),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusOrAction(
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    if (done) {
        Text(
            text = stringResource(R.string.onboarding_status_granted),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        OutlinedButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

private const val PAGE_COUNT = 5
private const val PAGE_INTRO = 0
private const val PAGE_NOTIFICATIONS = 1
private const val PAGE_BATTERY = 2
private const val PAGE_VENDOR = 3
private const val PAGE_READY = 4

@Composable
private fun pageTitle(page: Int): Int = when (page) {
    PAGE_INTRO -> R.string.onboarding_title
    PAGE_NOTIFICATIONS -> R.string.onboarding_step_notifications
    PAGE_BATTERY -> R.string.onboarding_step_battery
    PAGE_VENDOR -> R.string.onboarding_step_vendor
    else -> R.string.onboarding_ready_title
}

/** 厂商 ROM 名称（专有名词，四语言一致）。 */
internal fun vendorNameRes(vendor: VendorBgPolicy): Int = when (vendor) {
    VendorBgPolicy.XIAOMI -> R.string.keepalive_vendor_name_xiaomi
    VendorBgPolicy.HUAWEI -> R.string.keepalive_vendor_name_huawei
    VendorBgPolicy.HONOR -> R.string.keepalive_vendor_name_honor
    VendorBgPolicy.OPPO -> R.string.keepalive_vendor_name_oppo
    VendorBgPolicy.VIVO -> R.string.keepalive_vendor_name_vivo
    VendorBgPolicy.MEIZU -> R.string.keepalive_vendor_name_meizu
    VendorBgPolicy.SAMSUNG -> R.string.keepalive_vendor_name_samsung
    VendorBgPolicy.OTHER -> R.string.keepalive_vendor_name_other
}

/** 各厂商后台配置步骤（含国内定制系统注意事项）。 */
internal fun vendorStepsRes(vendor: VendorBgPolicy): Int = when (vendor) {
    VendorBgPolicy.XIAOMI -> R.string.keepalive_vendor_steps_xiaomi
    VendorBgPolicy.HUAWEI -> R.string.keepalive_vendor_steps_huawei
    VendorBgPolicy.HONOR -> R.string.keepalive_vendor_steps_honor
    VendorBgPolicy.OPPO -> R.string.keepalive_vendor_steps_oppo
    VendorBgPolicy.VIVO -> R.string.keepalive_vendor_steps_vivo
    VendorBgPolicy.MEIZU -> R.string.keepalive_vendor_steps_meizu
    VendorBgPolicy.SAMSUNG -> R.string.keepalive_vendor_steps_samsung
    VendorBgPolicy.OTHER -> R.string.keepalive_vendor_steps_other
}
