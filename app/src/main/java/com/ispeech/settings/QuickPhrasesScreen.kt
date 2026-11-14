
/** 云端简写添加弹窗：两个字段 + 校验（keyword 与 replyTemplate 必填）。 */
@Composable
private fun CloudShortcutEditorDialog(
    editor: CloudShortcutEditor,
    onChange: (CloudShortcutEditor) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
                    onValueChange = { onChange(editor.copy(keyword = it)) },
                    label = { Text(stringResource(R.string.quick_trigger)) },
                    singleLine = true,
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
                enabled = editor.keyword.isNotBlank() && editor.replyTemplate.isNotBlank(),
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