package cn.yzapp.androidcontainer.feature.compose

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yzapp.androidcontainer.core.billing.BillingFailure
import cn.yzapp.androidcontainer.core.billing.EntitlementState
import cn.yzapp.androidcontainer.core.billing.PlayAvailability
import cn.yzapp.androidcontainer.core.designsystem.component.ContentMaxWidth

/**
 * 模板包解锁页（方案 §4.2 / §6.5）。
 *
 * 政策要点：**一次性商品、非订阅**，文案里说清「免费路径依然可用」，
 * 且**不得出现任何外部支付引导**（含「前往官网购买」之类）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUnlockScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = remember(context) { findActivity(context) }
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val product by viewModel.product.collectAsStateWithLifecycle()
    val availability by viewModel.playAvailability.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()

    val unlocked = entitlement is EntitlementState.Unlocked

    LaunchedEffect(Unit) {
        viewModel.purchased.collect {
            Toast.makeText(context, R.string.pro_purchased_toast, Toast.LENGTH_LONG).show()
            onBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pro_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compose_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // 宽窗口下单列内容限宽居中（手机竖屏不受影响）
        ContentMaxWidth(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Text(
                text = product?.title ?: stringResource(R.string.pro_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.pro_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BenefitRow(stringResource(R.string.pro_benefit_all))
                    BenefitRow(stringResource(R.string.pro_benefit_future))
                    BenefitRow(stringResource(R.string.pro_benefit_permanent))
                }
            }

            // 价格来自 Play；加载中显示占位
            Text(
                text = product?.formattedPrice
                    ?.let { stringResource(R.string.pro_price, it) }
                    ?: stringResource(R.string.pro_price_loading),
                style = MaterialTheme.typography.titleMedium,
            )

            if (unlocked) {
                Text(
                    text = stringResource(R.string.pro_owned),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Button(
                    onClick = { activity?.let(viewModel::purchase) },
                    enabled = !busy && activity != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pro_buy)) }
            }
            OutlinedButton(
                onClick = viewModel::restore,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pro_restore)) }

            // 降级说明：无 Play 服务时明确告知「免费路径仍可用」
            if (availability == PlayAvailability.UNAVAILABLE) {
                Text(
                    text = stringResource(R.string.pro_error_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            failure?.let { reason ->
                Text(
                    text = reason.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.pro_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Play 错误码 → 礼貌文案（方案 §4.5：不甩锅、给下一步）。 */
@Composable
private fun BillingFailure.message(): String = when (this) {
    BillingFailure.PlayUnavailable -> stringResource(R.string.pro_error_unavailable)
    BillingFailure.NetworkUnavailable -> stringResource(R.string.pro_error_network)
    BillingFailure.ItemUnavailable -> stringResource(R.string.pro_error_item_unavailable)
    BillingFailure.AlreadyOwned -> stringResource(R.string.pro_error_already_owned)
    BillingFailure.Cancelled -> stringResource(R.string.pro_error_cancelled)
    BillingFailure.NothingToRestore -> stringResource(R.string.pro_error_nothing_to_restore)
    is BillingFailure.Unexpected -> stringResource(R.string.pro_error_generic, debugMessage.orEmpty())
}
