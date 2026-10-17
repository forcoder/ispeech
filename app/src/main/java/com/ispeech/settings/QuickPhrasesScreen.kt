package com.ispeech.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.R
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.settings.quick.QuickPhrasesViewModel

/** 快捷短语页：编辑本地快捷规则并保存到 filesDir/quick_phrases_user.txt。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPhrasesScreen(onBack: () -> Unit, onOpenSync: () -> Unit) {
    val viewModel: QuickPhrasesViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 编辑弹窗状态：null 表示未打开
    var editor by remember { mutableStateOf<QuickEditor?>(null) }

    // 统一展示 ViewModel 的提示信息
    LaunchedEffect(state.tip) {
        state.tip?.let { tip ->
            snackbarHostState.showSnackbar(tip)
            viewModel.consumeTip()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) {
                        Text(stringResource(R.string.btn_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editor = QuickEditor() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.btn_add))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.dirty) {
                            "共 ${state.entries.size} 条 · 有未保存修改"
                        } else {
                            "共 ${state.entries.size} 条"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.quick_user_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                OutlinedButton(onClick = onOpenSync) {
                    Text("从远程同步")
                }
            }
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.quick_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.entries) { entry ->
                        QuickEntryCard(
                            entry = entry,
                            onEdit = { editor = QuickEditor.from(entry) },
                            onDelete = { viewModel.remove(entry) },
                        )
                    }
                }
            }
        }
    }

    editor?.let { current ->
        QuickEditorDialog(
            editor = current,
            onChange = { editor = it },
            onDismiss = { editor = null },
            onConfirm = {
                viewModel.upsert(current.toEntry())
                editor = null
            },
        )
    }
}

/** 单条快捷规则卡片：展示 trigger / label / content / kind / mode / weight，提供编辑与删除。 */
@Composable
private fun QuickEntryCard(entry: QuickEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val kindText = lookupKindLabel(entry.kind)
    val modeText = quickModeLabel(entry.mode)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.trigger,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$kindText · $modeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = entry.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "权重 ${entry.weight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.btn_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.btn_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 编辑弹窗的临时状态（编辑期间不直接改列表，确认后再落列表）。 */
private data class QuickEditor(
    val isNew: Boolean = true,
    val trigger: String = "",
    val label: String = "",
    val content: String = "",
    val weightText: String = "100",
    val mode: QuickMatchMode = QuickMatchMode.PREFIX,
    val kind: LookupKind = LookupKind.QUICKAPPLY,
) {
    /** 转换为引擎条目；label 为空时回退为内容（与引擎解析规则一致）。 */
    fun toEntry(): QuickEntry {
        val trigger = trigger.trim()
        val label = label.trim()
        val content = content.trim()
        return QuickEntry(
            trigger = trigger,
            label = label.ifEmpty { content },
            content = content,
            weight = weightText.toIntOrNull() ?: 100,
            mode = mode,
            kind = kind,
        )
    }

    companion object {
        fun from(entry: QuickEntry) = QuickEditor(
            isNew = false,
            trigger = entry.trigger,
            label = entry.label,
            content = entry.content,
            weightText = entry.weight.toString(),
            mode = entry.mode,
            kind = entry.kind,
        )
    }
}

/** 新增 / 编辑弹窗：六个字段 + 校验（trigger 与 content 必填）。 */
@Composable
private fun QuickEditorDialog(
    editor: QuickEditor,
    onChange: (QuickEditor) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (editor.isNew) R.string.quick_add_rule else R.string.quick_edit_rule))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = editor.trigger,
                    onValueChange = { onChange(editor.copy(trigger = it)) },
                    label = { Text(stringResource(R.string.quick_trigger)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.label,
                    onValueChange = { onChange(editor.copy(label = it)) },
                    label = { Text(stringResource(R.string.quick_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.content,
                    onValueChange = { onChange(editor.copy(content = it)) },
                    label = { Text(stringResource(R.string.quick_content)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.weightText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() }) onChange(editor.copy(weightText = value))
                    },
                    label = { Text(stringResource(R.string.quick_weight)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EditorDropdown(
                    label = stringResource(R.string.quick_match_mode),
                    current = stringResource(quickModeRes(editor.mode)),
                    options = QuickMatchMode.entries.map { mode -> mode to quickModeRes(mode) },
                    onSelect = { onChange(editor.copy(mode = it)) },
                )
                EditorDropdown(
                    label = stringResource(R.string.quick_kind),
                    current = stringResource(lookupKindRes(editor.kind)),
                    // WUBIPHRASE / PINYIN 是引擎内部的候选档位，规则文件里不应出现
                    options = LookupKind.entries
                        .filter { it != LookupKind.WUBIPHRASE && it != LookupKind.PINYIN }
                        .map { kind -> kind to lookupKindRes(kind) },
                    onSelect = { onChange(editor.copy(kind = it)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = editor.trigger.isNotBlank() && editor.content.isNotBlank(),
            ) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

/** 下拉选择：options 为「值 → 字符串资源 id」。 */
@Composable
private fun <T> EditorDropdown(
    label: String,
    current: String,
    options: List<Pair<T, Int>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/** 匹配方式对应的字符串资源 id。 */
private fun quickModeRes(mode: QuickMatchMode): Int = when (mode) {
    QuickMatchMode.EXACT -> R.string.quick_mode_exact
    QuickMatchMode.PREFIX -> R.string.quick_mode_prefix
    QuickMatchMode.CONTAINS -> R.string.quick_mode_contains
    QuickMatchMode.REGEX -> R.string.quick_mode_regex
}

/** 规则类型对应的字符串资源 id。 */
private fun lookupKindRes(kind: LookupKind): Int = when (kind) {
    LookupKind.WUBI -> R.string.lookup_kind_wubi
    LookupKind.WUBIPHRASE -> R.string.lookup_kind_wubi_phrase
    LookupKind.PINYIN -> R.string.lookup_kind_pinyin
    LookupKind.PHRASE -> R.string.lookup_kind_phrase
    LookupKind.QUICKAPPLY -> R.string.lookup_kind_quickapply
}

/** 匹配方式展示文案。 */
@Composable
private fun quickModeLabel(mode: QuickMatchMode): String = stringResource(quickModeRes(mode))

/** 规则类型展示文案。 */
@Composable
private fun lookupKindLabel(kind: LookupKind): String = stringResource(lookupKindRes(kind))
