package com.ispeech.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.R
import com.ispeech.settings.home.HomeViewModel

/** 设置首页：品牌区 + 设置入口卡片列表。 */
@Composable
fun HomeScreen(
    onOpenDictionary: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAccount: (loggedIn: Boolean) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAsr: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    // 每次回到首页时刷新登录态摘要（登录成功后返回即可看到最新状态）
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 智语 logo 文字 + 版本
            Text(
                text = ISPEECH_NAME,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "版本 v$ISPEECH_VERSION",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(12.dp))

            SettingsEntryCard(
                icon = Icons.Filled.Search,
                title = "引擎与词典",
                subtitle = stringResource(R.string.settings_dict_desc),
                onClick = onOpenDictionary,
            )
            SettingsEntryCard(
                icon = Icons.Filled.Keyboard,
                title = "键盘与输入",
                subtitle = "键盘高度、候选数量、联想、用户词库",
                onClick = onOpenKeyboard,
            )
            SettingsEntryCard(
                icon = Icons.Filled.Edit,
                title = stringResource(R.string.quick_title),
                subtitle = stringResource(R.string.settings_quick_desc),
                onClick = onOpenQuickPhrases,
            )
            SettingsEntryCard(
                icon = Icons.Filled.AccountCircle,
                title = "csBaby 账号与同步",
                subtitle = state.accountSummary,
                onClick = { onOpenAccount(state.loggedIn) },
            )
            SettingsEntryCard(
                icon = Icons.Filled.BarChart,
                title = "输入统计",
                subtitle = "今日 / 累计字数、候选命中率与最常用词",
                onClick = onOpenStats,
            )
            SettingsEntryCard(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.theme_title),
                subtitle = stringResource(R.string.settings_theme_desc),
                onClick = onOpenTheme,
            )
            SettingsEntryCard(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.settings_about_desc),
                onClick = onOpenAbout,
            )
            SettingsEntryCard(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.asr_title),
                subtitle = stringResource(R.string.asr_description),
                onClick = onOpenAsr,
            )
        }
    }
}

/** 设置入口卡片：图标 + 标题 + 说明 + 右侧指示箭头。 */
@Composable
private fun SettingsEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
