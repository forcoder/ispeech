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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.data.UserDictEntry
import com.ispeech.settings.keyboard.KeyboardViewModel
import com.ispeech.settings.store.KeyboardSettings
import com.ispeech.settings.store.KeyboardSize

/** 用户词库列表最多展示的条数，避免长列表拖慢页面。 */
private const val MAX_VISIBLE_USER_WORDS = 50

/** 键盘与输入页：键盘高度、候选数量、联想开关 + 用户词库管理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardScreen(onBack: () -> Unit) {
    val viewModel: KeyboardViewModel = viewModel()
    val settings by viewModel.settings.collectAsState()
    val userWords by viewModel.userWords.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshUserWords() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("键盘与输入") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "调整软键盘的外观与输入行为，修改后立即生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            KeyboardSizeCard(
                current = settings,
                onSelect = { viewModel.setSize(it) },
            )

            CandidatePageSizeCard(
                current = settings.candidatePageSize,
                onSelect = { viewModel.setCandidatePageSize(it) },
            )

            // —— 输入行为开关 ——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "输入行为",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    KeyboardSwitchRow(
                        title = "震动反馈",
                        subtitle = "按键时轻微震动，方便盲打",
                        checked = settings.vibrate,
                    ) { viewModel.setVibrate(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "中文标点",
                        subtitle = "输入标点时自动使用中文全角标点（，。？！）",
                        checked = settings.chinesePunctuation,
                    ) { viewModel.setChinesePunctuation(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "全角输出",
                        subtitle = "字母与数字按全角字符上屏",
                        checked = settings.fullWidth,
                    ) { viewModel.setFullWidth(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "词语联想",
                        subtitle = "上屏后根据已输入的词推荐常用搭配",
                        checked = settings.association,
                    ) { viewModel.setAssociation(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "用户词频学习",
                        subtitle = "记住你选择过的词，用得越多越靠前",
                        checked = settings.learning,
                    ) { viewModel.setLearning(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "拼音混输",
                        subtitle = "五笔查不到时用拼音兜底（nerf 这类打不出五笔码的输入）",
                        checked = settings.pinyinMixed,
                    ) { viewModel.setPinyinMixed(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "模糊音",
                        subtitle = "z=zh、c=ch、s=sh、n=l、an=ang、en=eng、in=ing",
                        checked = settings.fuzzyPinyin,
                    ) { viewModel.setFuzzyPinyin(it) }
                    HorizontalDivider()
                    KeyboardSwitchRow(
                        title = "繁体输出",
                        subtitle = "上屏前把简体转成繁体（字符级转换，简繁词汇不消歧）",
                        checked = settings.traditionalOutput,
                    ) { viewModel.setTraditionalOutput(it) }
                }
            }

            UserDictCard(
                words = userWords,
                onRemove = { viewModel.removeUserWord(it) },
                onClear = { viewModel.clearUserWords() },
            )
        }
    }
}

/** 键盘高度档位选择卡片。 */
@Composable
private fun KeyboardSizeCard(current: KeyboardSettings, onSelect: (KeyboardSize) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            KeyboardCardTitle("键盘高度")
            KeyboardSize.entries.forEach { size ->
                KeyboardOptionRow(
                    selected = current.size == size,
                    title = size.label,
                    detail = "${size.heightDp} dp",
                    onClick = { onSelect(size) },
                )
            }
        }
    }
}

/** 每页候选数选择卡片：只允许 5 / 7 / 9。 */
@Composable
private fun CandidatePageSizeCard(current: Int, onSelect: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            KeyboardCardTitle("每页候选数")
            listOf(5, 7, 9).forEach { size ->
                KeyboardOptionRow(
                    selected = current == size,
                    title = "$size 个",
                    detail = "",
                    onClick = { onSelect(size) },
                )
            }
        }
    }
}

/** 用户词库卡片：展示学习记录，可单条删除或整体清空。 */
@Composable
private fun UserDictCard(
    words: List<UserDictEntry>,
    onRemove: (UserDictEntry) -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            KeyboardCardTitle("用户词库")
            Text(
                text = "输入法自动记住你选择过的词，用得越多越靠前",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (words.isEmpty()) {
                Text(
                    text = "暂无学习记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                words.take(MAX_VISIBLE_USER_WORDS).forEach { entry ->
                    HorizontalDivider()
                    UserWordRow(entry = entry, onRemove = { onRemove(entry) })
                }
                if (words.size > MAX_VISIBLE_USER_WORDS) {
                    Text(
                        text = "仅显示前 $MAX_VISIBLE_USER_WORDS 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClear) {
                        Text(text = "清空用户词库", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 单条用户词条：code → text ×次数 + 删除按钮。 */
@Composable
private fun UserWordRow(entry: UserDictEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${entry.code} → ${entry.text} ×${entry.count}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onRemove) {
            Text(text = "删除")
        }
    }
}

/** 卡片内的小标题。 */
@Composable
private fun KeyboardCardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 单选行：整行可点击，右侧 RadioButton。 */
@Composable
private fun KeyboardOptionRow(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 带开关的设置行：整行可点击，右侧 Switch。 */
@Composable
private fun KeyboardSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
