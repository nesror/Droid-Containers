package cn.yzapp.androidcontainer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yzapp.androidcontainer.core.designsystem.R

/**
 * 推荐入口卡：把用户引到更省事的相邻功能（容器页 → 编排，编排页 → 模板库）。
 *
 * 与普通内容卡作区分：secondaryContainer 底色 + 圆形图标 + “Recommended” 上标，
 * 整卡可点击、尾部箭头暗示会跳转，一眼能看出这是“建议试试”而不是列表数据。
 */
@Composable
fun RecommendationCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = contentColor,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .background(contentColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.ds_recommended),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
