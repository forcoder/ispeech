package com.ispeech.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.R
import com.ispeech.data.UserDictMode
import com.ispeech.settings.dictionary.DictionaryViewModel

/** 引擎与词典页：展示五笔引擎统计 + 规则开关（DataStore 持久化）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    val viewModel: DictionaryViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    // 用户词库导入 / 导出：系统文件选择器（SAF），不申请任何存储权限
    var importMode by remember { mutableStateOf(UserDictMode.WUBI) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importUserWords(it, importMode) } }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.exportUserWords(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dict_title)) },
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
            // —— 引擎统计 ——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dict_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val statsError = state.statsError
                    when {
                        state.statsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("正在加载码表数据…", style = MaterialTheme.typography.bodyMedium)
                        }
                        statsError != null -> Text(
                            text = "引擎数据加载失败：$statsError",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> {
                            StatRow(stringResource(R.string.dict_entry_count, state.entryCount))
                            StatRow(stringResource(R.string.dict_code_count, state.codeCount))
                            StatRow(stringResource(R.string.dict_single_count, state.singleCount))
                            StatRow(stringResource(R.string.dict_phrase_count, state.phraseCount))
                            if (state.summary.isNotBlank()) {
                                Text(
                                    text = state.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // —— 规则开关 ——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "规则开关",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    SwitchRow(
                        title = "启用快捷短语",
                        subtitle = "允许输入时触发本地快捷规则",
                        checked = state.enableQuickPhrases,
                    ) { viewModel.setEnableQuickPhrases(it) }
                    HorizontalDivider()
                    SwitchRow(
                        title = "启用远程规则",
                        subtitle = "允许触发从 csBaby 同步的远程关键词规则",
                        checked = state.enableRemoteRules,
                    ) { viewModel.setEnableRemoteRules(it) }
                    HorizontalDivider()
                    SwitchRow(
                        title = "单字母不触发快捷规则",
                        subtitle = "单个字母输入不触发快捷规则，避免误触（默认开启）",
                        checked = state.singleLetterNoTrigger,
                    ) { viewModel.setSingleLetterNoTrigger(it) }
                }
            }

            // —— 用户词库（导入 / 导出）——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.dict_user_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Text(
                        text = stringResource(R.string.dict_user_count, state.userWordCount),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dict_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            importMode = UserDictMode.WUBI
                            importLauncher.launch(arrayOf("text/*", "text/plain"))
                        }) {
                            Text(stringResource(R.string.dict_import_wubi))
                        }
                        TextButton(onClick = {
                            importMode = UserDictMode.PINYIN
                            importLauncher.launch(arrayOf("text/*", "text/plain"))
                        }) {
                            Text(stringResource(R.string.dict_import_pinyin))
                        }
                        TextButton(onClick = { exportLauncher.launch(EXPORT_FILE_NAME) }) {
                            Text(stringResource(R.string.dict_export))
                        }
                    }
                    state.lastImport?.let { imported ->
                        Text(
                            text = stringResource(
                                R.string.dict_import_result,
                                imported.total,
                                imported.updated,
                                imported.skipped,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    state.importError?.let { message ->
                        Text(
                            text = stringResource(R.string.dict_import_failed, message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    state.exportError?.let { message ->
                        Text(
                            text = stringResource(R.string.dict_export_failed, message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 导出的默认文件名：与 filesDir 下的落盘文件名一致，便于再导入。 */
private const val EXPORT_FILE_NAME = "ispeech_user_dict.txt"

/** 单行统计文本（文案由字符串资源带参格式化）。 */
@Composable
private fun StatRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
}

/** 带开关的设置行：整行可点击，右侧 Switch。 */
@Composable
private fun SwitchRow(
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
