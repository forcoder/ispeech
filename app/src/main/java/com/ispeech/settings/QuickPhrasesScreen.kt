package com.ispeech.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
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
import com.ispeech.engine.QuickTrigger
import com.ispeech.engine.QuickTriggerIssue
import com.ispeech.settings.quick.QuickPhrasesUiState
import com.ispeech.settings.quick.QuickPhrasesViewModel
import com.ispeech.settings.quick.RemoteRuleInfo

/**
 * 快捷字母校验只有 [QuickTrigger.isValidShortcutTrigger] 一个实现（ADR-0002 C1-2）：
 * 本文件不再写第二份长度判定。VOCA-729 起位数不限制，长度只用于可达性提示。
 *
 * 这里只保留「还差什么」到文案资源的映射，供禁用原因与错误提示共用（C1-7），
 * ViewModel 与界面同一个来源，避免同一问题两处不同说法。
 */
@StringRes
internal fun triggerIssueRes(issue: QuickTriggerIssue): Int = when (issue) {
    QuickTriggerIssue.EMPTY -> R.string.quick_trigger_reason_empty
    QuickTriggerIssue.NOT_LETTERS -> R.string.quick_trigger_reason_letters
}

/**
 * 快捷字母输入框下方的引导文案（C1-7 禁用原因 + C1-7 可达性提示 + C1-9 误弹警示）。
 *
 * 三条都非阻断：>4 位照样能存，只是五笔态敲不全；1 位字母若用前缀 / 包含匹配会被防误触守卫拦下；
 * 选了前缀 / 包含匹配的长正文可能压过正在输入的词。界面把这件事说出来，而不是靠「不让存」来回避。
 */
@Composable
private fun QuickTriggerGuidance(trigger: String, mode: QuickMatchMode? = null) {
    val normalized = trigger.trim().lowercase()
    val issue = QuickTrigger.issue(normalized)
    if (normalized.isEmpty()) {
        Text(
            text = stringResource(R.string.quick_trigger_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (issue != null) {
        // 按钮禁用时说明「还差什么」，不要只让用户对着一个灰掉的确认键猜
        Text(
            text = stringResource(R.string.quick_trigger_invalid, stringResource(triggerIssueRes(issue))),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Text(
        text = stringResource(R.string.quick_trigger_hint),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (normalized.length == 1 && mode != null &&
        mode != QuickMatchMode.EXACT && mode != QuickMatchMode.REGEX
    ) {
        Text(
            text = stringResource(R.string.quick_trigger_single_letter_warn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (normalized.length > QuickTrigger.MAX_TRIGGER_LENGTH) {
        Text(
            text = stringResource(R.string.quick_trigger_unreachable_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (QuickTrigger.isBeyondWubiBuffer(normalized)) {
        Text(
            text = stringResource(R.string.quick_trigger_reachable_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mode != null && QuickTrigger.mayMisfire(normalized, mode)) {
            Text(
                text = stringResource(R.string.quick_trigger_prefix_long_content_warn),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

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
    // 冲突详情：本地条目 + 远端条目成对传入，两个动作都由用户点（C3-7）
    var conflictEntry by remember { mutableStateOf<Pair<QuickEntry, QuickEntry>?>(null) }
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
                        // 同 trigger 折叠后卡片正文取本地内容（C2-3），来源信息一律按展示 id 取
                        val info = state.remoteInfo[match.id]
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
                            onConflict = { info?.let { conflictEntry = match.entry to it.original } },
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

    conflictEntry?.let { (local, remote) ->
        QuickConflictDetailDialog(
            local = local,
            remote = remote,
            onDismiss = { conflictEntry = null },
            onAdoptRemote = {
                viewModel.adoptRemoteContent(local, remote.content)
                conflictEntry = null
            },
            onPushLocal = {
                viewModel.overwriteRemoteWithLocal(local.trigger)
                conflictEntry = null
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
/**
 * 冲突详情弹窗（ADR-0002 决策 2 / C3-7）：两侧正文逐字可读，两个动作各管一边。
 *
 * 「采用云端正文」只写本地，云端一个字节不动；「以本地为准并更新云端」走显式推送入口，
 * 推后复验，未生效不清空冲突。默认按钮什么都不做 —— 自动拉平任一方向都是数据丢失。
 */
@Composable
private fun QuickConflictDetailDialog(
    local: QuickEntry,
    remote: QuickEntry,
    onDismiss: () -> Unit,
    onAdoptRemote: () -> Unit,
    onPushLocal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_conflict_detail_title, local.trigger)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.quick_conflict_local_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = local.content,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.quick_conflict_remote_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = remote.content,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onPushLocal) {
                Text(stringResource(R.string.quick_conflict_push_local))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
                TextButton(onClick = onAdoptRemote) {
                    Text(stringResource(R.string.quick_conflict_adopt_remote))
                }
            }
        },
    )
}

@Composable
private fun QuickEntryCard(
    entry: PhraseMatch,
    info: RemoteRuleInfo?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onConflict: () -> Unit,
) {
    val rule = entry.entry
    val isRemote = info != null
    val overrideTrigger = info?.overrideTrigger
    val kindText = lookupKindLabel(rule.kind)
    val modeText = quickModeLabel(rule.mode)
    // C2-3：折叠后的卡片正文取本地内容，来源仍要写明它是远端规则（并标注已覆盖）
    val sourceText = when {
        info == null -> rule.source
        overrideTrigger != null -> stringResource(R.string.quick_source_remote_overridden)
        else -> QuickSource.REMOTE
    }
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
                        text = "$sourceText · $kindText · $modeText",
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
                if (info?.conflict == true) {
                    // C2-5：同 trigger 但正文不一致 —— 不自动改任何一侧，先让人看见
                    Text(
                        text = stringResource(R.string.quick_conflict_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.btn_edit))
            }
            if (isRemote) {
                if (info?.conflict == true) {
                    TextButton(onClick = onConflict) {
                        Text(stringResource(R.string.quick_conflict_badge))
                    }
                }
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
    val triggerValid = QuickTrigger.isValidShortcutTrigger(editor.trigger)
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
                        // 非法原因 / >4 位可达性 / 误弹警示都在这一处（C1-7、C1-9）
                        QuickTriggerGuidance(editor.trigger, editor.mode)
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
    val triggerValid = QuickTrigger.isValidShortcutTrigger(editor.trigger)
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
                        // 覆盖条目的匹配方式由 QuickTrigger 按长度给出，用户不选，因此这里只给非法原因与可达性提示
                        QuickTriggerGuidance(editor.trigger)
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
    val keywordValid = QuickTrigger.isValidShortcutTrigger(editor.keyword)
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
                        // 云端简写同样是快捷字母：非法原因与 >4 位可达性口径与本地一致（C1-7）
                        QuickTriggerGuidance(editor.keyword)
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