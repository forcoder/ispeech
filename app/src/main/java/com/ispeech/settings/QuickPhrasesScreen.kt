package com.ispeech.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.R
import com.ispeech.data.PhraseHitField
import com.ispeech.data.PhraseMatch
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickSource
import com.ispeech.settings.quick.QuickPhrasesUiState
import com.ispeech.settings.quick.QuickPhrasesViewModel
import com.ispeech.settings.quick.RemoteRuleInfo

/**
 * 快捷字母校验：2–4 位小写字母。
 *
 * 上限 4 与输入法五笔态的组合缓冲区 MAX_CODE_LENGTH 一致：超过 4 位时第 5 个字母会先上屏
 * 当前首选再重新起码，完整简写永远打不出来，也就拿不到「精确命中排第一」。
 * 数字 / 符号在五笔态直接上屏、不进入编码缓冲，所以同样不允许。
 */
internal fun isValidShortcutTrigger(trigger: String): Boolean =
    trigger.length in 2..4 && trigger.all { it in 'a'..'z' }

/**
 * 通用短语 CSV 的文件选择器 MIME 白名单。
 *
 * 不同来源报的 MIME 不一样：文本编辑器 / 网盘多为 `text/plain`，Excel 是 `application/vnd.ms-excel`，
 * 部分文件管理器给 `application/octet-stream`。漏一个用户就看不到自己刚下载的 CSV，
 * 所以逐个列全而不是用通配符（通配符会把二进制文件也放进选择器）。
 */
private val PHRASE_CSV_MIME_TYPES = arrayOf(
    "text/plain",
    "text/csv",
    "text/comma-separated-values",
    "text/x-csv",
    "application/csv",
    "application/vnd.ms-excel",
    "application/octet-stream",
)

/** 快捷短语页：搜索 / 编辑本地快捷规则，并可增删 csBaby 云端简写。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPhrasesScreen(onBack: () -> Unit, onOpenSync: () -> Unit) {
    val viewModel: QuickPhrasesViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 编辑弹窗状态：null 表示未打开
    var editor by remember { mutableStateOf<QuickEditor?>(null) }
    // 云端简写弹窗状态：null 表示未打开
    var cloudEditor by remember { mutableStateOf<CloudShortcutEditor?>(null) }
    // 远端规则快捷字母弹窗状态：null 表示未打开
    var remoteEditor by remember { mutableStateOf<RemoteShortcutEditor?>(null) }
    // 「新增」类型选择弹窗：本地规则 / 云端简写
    var choosingAddKind by remember { mutableStateOf(false) }

    // 通用短语 CSV 导入：系统文件选择器（SAF），不申请存储权限
    val csvImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importPhraseCsv(it) } }

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
                        Text(stringResource(R.string.quick_save_sync))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { choosingAddKind = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.btn_add))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            QuickSearchBar(
                state = state,
                onKeyword = viewModel::setKeyword,
                onClear = viewModel::clearKeyword,
            )
            // 统计行 + 两个入口：窄屏上按钮换行到文字下方，不挤压“本地 / 远端”说明
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = if (state.keyword.isBlank()) {
                        stringResource(R.string.quick_search_smart, state.matches.size)
                    } else {
                        stringResource(R.string.quick_search_count, state.matches.size, state.keyword)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.quick_source_files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = { csvImportLauncher.launch(PHRASE_CSV_MIME_TYPES) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(stringResource(R.string.quick_import_csv))
                    }
                    OutlinedButton(
                        onClick = onOpenSync,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("从远程同步")
                    }
                }
            }
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.matches.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.keyword.isBlank()) {
                            stringResource(R.string.quick_empty)
                        } else {
                            stringResource(R.string.quick_search_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.matches, key = { match -> match.id }) { match ->
                        val info = state.remoteInfo[match.id]
                            ?.takeIf { match.entry.source == QuickSource.REMOTE }
                        QuickEntryCard(
                            entry = match,
                            info = info,
                            onEdit = {
                                if (info != null) {
                                    remoteEditor = RemoteShortcutEditor(
                                        original = info.original,
                                        trigger = info.overrideTrigger.orEmpty(),
                                    )
                                } else {
                                    editor = QuickEditor.from(match.entry)
                                }
                            },
                            onDelete = { viewModel.remove(match.entry) },
                            onRestore = { info?.let { viewModel.removeRemoteOverride(it.original) } },
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

    cloudEditor?.let { current ->
        CloudShortcutEditorDialog(
            editor = current,
            onChange = { cloudEditor = it },
            onDismiss = { cloudEditor = null },
            onConfirm = {
                viewModel.addCloudShortcut(current.keyword.trim(), current.replyTemplate.trim())
                cloudEditor = null
            },
        )
    }

    remoteEditor?.let { current ->
        RemoteShortcutEditorDialog(
            editor = current,
            onChange = { remoteEditor = it },
            onDismiss = { remoteEditor = null },
            onConfirm = {
                viewModel.setRemoteShortcut(current.original, current.trigger)
                remoteEditor = null
            },
        )
    }

    if (choosingAddKind) {
        AlertDialog(
            onDismissRequest = { choosingAddKind = false },
            title = { Text(stringResource(R.string.quick_add_cloud_shortcut)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            choosingAddKind = false
                            editor = QuickEditor()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.quick_add_local_rule))
                    }
                    OutlinedButton(
                        onClick = {
                            choosingAddKind = false
                            cloudEditor = CloudShortcutEditor()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.btn_add_cloud_shortcut))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosingAddKind = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** 搜索栏：输入即过滤 + 智能联想，命中数量由调用方展示。 */
@Composable
private fun QuickSearchBar(
    state: QuickPhrasesUiState,
    onKeyword: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = state.keyword,
        onValueChange = onKeyword,
        placeholder = { Text(stringResource(R.string.quick_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (state.keyword.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.btn_cancel))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused && state.keyword.isEmpty()) {
                    // 当获得焦点且搜索框为空时，自动弹出软键盘
                    // （实际软键盘弹出由系统控制，这里只请求焦点）
                }
            },
    )
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/** 单条快捷规则卡片：展示 trigger / label / content / kind / mode / weight，命中处高亮。 */
@Composable
private fun QuickEntryCard(
    entry: PhraseMatch,
    info: RemoteRuleInfo?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    val rule = entry.entry
    val isRemote = info != null
    val overrideTrigger = info?.overrideTrigger
    val kindText = lookupKindLabel(rule.kind)
    val modeText = quickModeLabel(rule.mode)
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
                        text = highlight(rule.trigger, spansIn(entry, PhraseHitField.TRIGGER)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${rule.source} · $kindText · $modeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (entry.starred) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "★",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = highlight(rule.label, spansIn(entry, PhraseHitField.LABEL)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = highlight(rule.content, spansIn(entry, PhraseHitField.CONTENT)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "权重 ${rule.weight} · 用 ${entry.count} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (overrideTrigger != null) {
                    Text(
                        text = stringResource(R.string.quick_remote_overridden, overrideTrigger),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.btn_edit))
            }
            if (isRemote) {
                if (overrideTrigger != null) {
                    TextButton(onClick = onRestore) {
                        Text(stringResource(R.string.quick_remote_restore))
                    }
                }
            } else {
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
}

/**
 * 取某字段上的命中区间：只有命中字段才带区间，其余字段返回空（不高亮）。
 * 区间为 Kotlin 闭区间，来自 [PhraseMatch.spans]。
 */
private fun spansIn(match: PhraseMatch, field: PhraseHitField): List<IntRange> =
    if (match.field == field) match.spans else emptyList()

/** 按命中区间高亮文本；无区间时原样返回。 */
@Composable
private fun highlight(text: String, spans: List<IntRange>): androidx.compose.ui.text.AnnotatedString {
    if (spans.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text)
    val background = MaterialTheme.colorScheme.primaryContainer
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            val start = span.first.coerceIn(0, text.length)
            val end = (span.last + 1).coerceIn(start, text.length)
            if (start < end) {
                addStyle(
                    style = androidx.compose.ui.text.SpanStyle(background = background, fontWeight = FontWeight.SemiBold),
                    start = start,
                    end = end,
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
        val trigger = trigger.trim().lowercase()
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
            trigger = entry.trigger.lowercase(),
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
    val triggerValid = isValidShortcutTrigger(editor.trigger)
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
                    onValueChange = { raw ->
                        // 只保留小写字母：大写自动转小写，数字 / 符号在五笔态打不出来，直接过滤
                        onChange(editor.copy(trigger = raw.lowercase().filter { it in 'a'..'z' }))
                    },
                    label = { Text(stringResource(R.string.quick_trigger)) },
                    supportingText = {
                        val showError = editor.trigger.isNotEmpty() && !triggerValid
                        Text(
                            text = stringResource(
                                if (showError) R.string.quick_trigger_error else R.string.quick_trigger_hint
                            ),
                            color = if (showError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    isError = editor.trigger.isNotEmpty() && !triggerValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
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
                enabled = triggerValid && editor.content.isNotBlank(),
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

/** 远端规则快捷字母弹窗的临时状态。 */
private data class RemoteShortcutEditor(
    val original: QuickEntry,
    val trigger: String = "",
)

/** 远端规则快捷字母弹窗：远端内容只读，只允许设置 / 修改快捷字母。 */
@Composable
private fun RemoteShortcutEditorDialog(
    editor: RemoteShortcutEditor,
    onChange: (RemoteShortcutEditor) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val triggerValid = isValidShortcutTrigger(editor.trigger)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_remote_shortcut_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.quick_remote_shortcut_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${stringResource(R.string.quick_label)}：${editor.original.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = editor.original.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = editor.trigger,
                    onValueChange = { raw ->
                        // 与本地规则弹窗同一套输入约束：只保留小写字母
                        onChange(editor.copy(trigger = raw.lowercase().filter { it in 'a'..'z' }))
                    },
                    label = { Text(stringResource(R.string.quick_trigger)) },
                    supportingText = {
                        val showError = editor.trigger.isNotEmpty() && !triggerValid
                        Text(
                            text = stringResource(
                                if (showError) R.string.quick_trigger_error else R.string.quick_trigger_hint
                            ),
                            color = if (showError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    isError = editor.trigger.isNotEmpty() && !triggerValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = triggerValid) {
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

/** 云端简写弹窗的临时状态（关键字与回复模板，对应 csBaby 的 keyword / replyTemplate）。 */
private data class CloudShortcutEditor(
    val keyword: String = "",
    val replyTemplate: String = "",
)

/** 云端简写添加弹窗：两个字段 + 校验（keyword 与 replyTemplate 必填）。 */
@Composable
private fun CloudShortcutEditorDialog(
    editor: CloudShortcutEditor,
    onChange: (CloudShortcutEditor) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 云端 keyword 同步回本地后就是快捷字母，校验与本地规则弹窗保持一致
    val keywordValid = isValidShortcutTrigger(editor.keyword)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_add_cloud_rule)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = editor.keyword,
                    onValueChange = { raw ->
                        onChange(editor.copy(keyword = raw.lowercase().filter { it in 'a'..'z' }))
                    },
                    label = { Text(stringResource(R.string.quick_trigger)) },
                    supportingText = {
                        val showError = editor.keyword.isNotEmpty() && !keywordValid
                        Text(
                            text = stringResource(
                                if (showError) R.string.quick_trigger_error else R.string.quick_trigger_hint
                            ),
                            color = if (showError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    isError = editor.keyword.isNotEmpty() && !keywordValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.replyTemplate,
                    onValueChange = { onChange(editor.copy(replyTemplate = it)) },
                    label = { Text(stringResource(R.string.quick_content)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = keywordValid && editor.replyTemplate.isNotBlank(),
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