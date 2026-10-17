package com.ispeech.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.data.CommitType
import com.ispeech.data.DayStats
import com.ispeech.data.InputStats
import com.ispeech.data.StatsSnapshot
import com.ispeech.data.WordStat
import com.ispeech.settings.stats.StatsViewModel
import kotlin.math.round

/** 最常用词卡片最多展示的条数，避免长列表拖慢页面（数据仍按 Top 50 统计）。 */
private const val MAX_VISIBLE_WORDS = 20

/**
 * 输入统计页（只读）：今日 / 累计字数、候选命中率、上屏类型分布、最常用词 Top 50 + 重置按钮。
 *
 * 数据只读本机 filesDir/input_stats.txt（见 [InputStats]），不上传、不联网。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val viewModel: StatsViewModel = viewModel()
    val snapshot by viewModel.snapshot.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    // 每次进入页面刷新一次（输入法在另一个界面写入统计）
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("输入统计") },
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
                text = "输入时自动统计字数与候选命中率，数据只保存在本机，不会上传。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TodayCard(snapshot)

            TotalCard(snapshot)

            TypeCard(snapshot.today, title = "今日上屏类型")

            TopWordsCard(snapshot.topWords)

            ResetCard(onReset = { confirmReset = true })
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置输入统计？") },
            text = {
                Text("将清空今日与累计字数、候选命中率、上屏类型分布与最常用词，且不可恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.reset()
                    },
                ) {
                    Text(text = "重置", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
        )
    }
}

/** 今日统计卡片。 */
@Composable
private fun TodayCard(snapshot: StatsSnapshot) {
    StatsCard(
        title = "今日",
        rows = listOf(
            "输入字数" to "${snapshot.today.chars}",
            "上屏次数" to "${snapshot.today.commits}",
            "候选命中率" to percentText(snapshot.today.hitRate),
        ),
        footnote = if (snapshot.today.date.isEmpty()) {
            "命中率 = 上屏次数 / 候选查询次数，今天还没有记录"
        } else {
            "${snapshot.today.date} · 命中率 = 上屏次数 / 候选查询次数（今天查询 ${snapshot.today.queries} 次）"
        },
    )
}

/** 累计统计卡片。 */
@Composable
private fun TotalCard(snapshot: StatsSnapshot) {
    StatsCard(
        title = "累计",
        rows = listOf(
            "输入字数" to "${snapshot.total.chars}",
            "上屏次数" to "${snapshot.total.commits}",
            "候选命中率" to percentText(snapshot.hitRate),
            "统计天数" to "${snapshot.trackedDays}",
        ),
        footnote = "累计值由每日分桶求和得到（最多保留 400 天）",
    )
}

/** 某日上屏类型分布卡片。 */
@Composable
private fun TypeCard(day: DayStats, title: String) {
    StatsCard(
        title = title,
        rows = CommitType.entries.map { type ->
            typeLabel(type) to "${day.commitsOf(type)} 次 · ${day.charsOf(type)} 字"
        },
        footnote = "类型按上屏来源区分：候选 / 英文 / 标点 / 短语 / 表情",
    )
}

/** 最常用词卡片。 */
@Composable
private fun TopWordsCard(words: List<WordStat>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CardTitle("最常用词 Top ${InputStats.TOP_WORDS_LIMIT}")
            CardHint("统计候选与短语上屏的词语，按频次降序，频次相同时按文字升序")
            if (words.isEmpty()) {
                Text(
                    text = "暂无统计",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                words.take(MAX_VISIBLE_WORDS).forEachIndexed { index, word ->
                    HorizontalDivider()
                    WordRow(rank = index + 1, word = word)
                }
                if (words.size > MAX_VISIBLE_WORDS) {
                    Text(
                        text = "仅显示前 $MAX_VISIBLE_WORDS 条（共 ${words.size} 条）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 重置卡片：说明影响范围 + 危险操作按钮。 */
@Composable
private fun ResetCard(onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CardTitle("重置")
            CardHint("只清空输入统计，不影响用户词库、快捷短语与剪贴板历史")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReset) {
                    Text(text = "重置全部统计", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 通用统计卡片：标题 + 若干「标签 - 数值」行 + 脚注。 */
@Composable
private fun StatsCard(
    title: String,
    rows: List<Pair<String, String>>,
    footnote: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CardTitle(title)
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 单词行：名次 + 词 + 频次。 */
@Composable
private fun WordRow(rank: Int, word: WordStat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = word.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "×${word.count}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 卡片内的小标题（与 KeyboardScreen 的样式保持一致）。 */
@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 卡片内的说明文字。 */
@Composable
private fun CardHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** 上屏类型的中文名。 */
private fun typeLabel(type: CommitType): String = when (type) {
    CommitType.CANDIDATE -> "候选上屏"
    CommitType.ENGLISH -> "英文"
    CommitType.PUNCTUATION -> "标点 / 符号"
    CommitType.PHRASE -> "短语 / 长文本"
    CommitType.EMOJI -> "表情"
}

/** 命中率百分比（保留一位小数，不用 String.format 以免受系统区域设置影响）。 */
private fun percentText(rate: Double): String {
    val clamped = rate.coerceIn(0.0, 1.0) * 100.0
    val rounded = round(clamped * 10.0) / 10.0
    val whole = rounded.toInt()
    val tenth = round((rounded - whole) * 10.0).toInt()
    return if (tenth <= 0) "$whole%" else "$whole.$tenth%"
}
