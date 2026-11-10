package com.ispeech.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ispeech.settings.store.AiProvider

/**
 * AI 建议设置页：选择大模型来源、填写 Base URL / 模型 / Key，查看学习采纳率。
 *
 * 只有直连来源（非 csBaby 云端）才需要 Base URL / 模型 / Key；
 * 快捷短语命中不经过模型，因此不受这里的配置影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val viewModel: AiSettingsViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 建议") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(text = "‹ 返回", style = MaterialTheme.typography.titleMedium)
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
                text = "点击 AI 键时：先匹配快捷短语，没有命中再用这里配置的大模型生成建议回复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AiSectionTitle("模型来源")
            Column(modifier = Modifier.selectableGroup()) {
                AiProvider.entries.forEach { provider ->
                    AiProviderOption(
                        provider = provider,
                        selected = state.draft.provider == provider,
                        onSelect = { viewModel.updateProvider(provider) },
                    )
                }
            }

            if (state.draft.provider.isDirect) {
                OutlinedTextField(
                    value = state.draft.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://example.com/v1/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.draft.model,
                    onValueChange = viewModel::updateModel,
                    label = { Text("模型名") },
                    placeholder = { Text("model-name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.draft.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Key 只保存在本机，用于直接请求所选厂商。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "csBaby 云端由后端选择模型，需要先在「账号与同步」里登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AiSectionTitle("学习与采纳")
            AiSwitchRow(
                title = "学习我的输入",
                desc = "把你采纳过的回复记在本机，下次生成时模仿你的口气。不上传云端。",
                checked = state.draft.learning,
                onCheckedChange = viewModel::updateLearning,
            )
            Text(
                text = "已生成 ${state.generated} 条，采纳 ${state.accepted} 条，采纳率 ${state.adoptionText}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = { viewModel.save() },
                enabled = state.dirty,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }

            Button(
                onClick = { viewModel.clearLearning() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空学习数据")
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
private fun AiSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 模型来源单选项。 */
@Composable
private fun AiProviderOption(
    provider: AiProvider,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = provider.label, style = MaterialTheme.typography.bodyLarge)
            if (provider.isDirect) {
                Text(
                    text = provider.baseUrl.ifEmpty { "需要自填 Base URL" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 带说明的开关行。 */
@Composable
private fun AiSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}