package com.ispeech.settings.asr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.ispeech.settings.store.AsrEngine
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.R
import com.ispeech.settings.store.AsrLanguage
import com.ispeech.settings.store.AsrSettings

/** ASR 模型设置页：选择语音识别引擎、语言与识别参数。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrSettingsScreen(onBack: () -> Unit) {
    val viewModel: AsrSettingsViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.asr_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(
                            text = "‹ 返回",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.asr_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.asr_engine_label))
            Column(modifier = Modifier.selectableGroup()) {
                EngineOption(
                    title = stringResource(R.string.asr_engine_system),
                    desc = stringResource(R.string.asr_engine_system_desc),
                    selected = state.draft.engine == AsrEngine.SYSTEM,
                    onSelect = { viewModel.updateEngine(AsrEngine.SYSTEM) },
                )
                EngineOption(
                    title = stringResource(R.string.asr_engine_custom),
                    desc = stringResource(R.string.asr_engine_custom_desc),
                    selected = state.draft.engine == AsrEngine.CUSTOM,
                    onSelect = { viewModel.updateEngine(AsrEngine.CUSTOM) },
                )
                EngineOption(
                    title = stringResource(R.string.asr_engine_funasr),
                    desc = stringResource(R.string.asr_engine_funasr_desc),
                    selected = state.draft.engine == AsrEngine.FUNASR,
                    onSelect = { viewModel.updateEngine(AsrEngine.FUNASR) },
                )
                EngineOption(
                    title = stringResource(R.string.asr_engine_vosk),
                    desc = stringResource(R.string.asr_engine_vosk_desc),
                    selected = state.draft.engine == AsrEngine.VOSK,
                    onSelect = { viewModel.updateEngine(AsrEngine.VOSK) },
                )
            }

            if (state.draft.engine == AsrEngine.CUSTOM) {
                OutlinedTextField(
                    value = state.draft.serviceComponent,
                    onValueChange = viewModel::updateServiceComponent,
                    label = { Text(stringResource(R.string.asr_component_label)) },
                    placeholder = { Text(stringResource(R.string.asr_component_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.draft.model,
                    onValueChange = viewModel::updateModel,
                    label = { Text(stringResource(R.string.asr_model_label)) },
                    placeholder = { Text(stringResource(R.string.asr_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionTitle(stringResource(R.string.asr_language_label))
            Column(modifier = Modifier.selectableGroup()) {
                AsrLanguage.entries.forEach { language ->
                    LanguageOption(
                        language = language,
                        selected = state.draft.language == language,
                        onSelect = { viewModel.updateLanguage(language) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.asr_options_label))
            SwitchRow(
                title = stringResource(R.string.asr_partial_label),
                desc = stringResource(R.string.asr_partial_desc),
                checked = state.draft.partialResults,
                onCheckedChange = viewModel::updatePartialResults,
            )
            SwitchRow(
                title = stringResource(R.string.asr_offline_label),
                desc = stringResource(R.string.asr_offline_desc),
                checked = state.draft.preferOffline,
                onCheckedChange = viewModel::updatePreferOffline,
            )

            Button(
                onClick = { viewModel.save() },
                enabled = state.dirty,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_save))
            }

            if (state.message != null) {
                Text(
                    text = state.message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 小节标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 引擎单选项：单选圈 + 标题 + 说明。 */
@Composable
private fun EngineOption(
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 语言单选项。 */
@Composable
private fun LanguageOption(language: AsrLanguage, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = language.label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 开关行：标题 + 说明 + Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
