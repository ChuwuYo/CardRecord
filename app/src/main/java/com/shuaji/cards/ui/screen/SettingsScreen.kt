package com.shuaji.cards.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.ui.ViewModelFactories
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页。
 *
 * 结构：
 * - TopAppBar：「设置」+ 返回按钮
 * - 列表：
 *   - 数据 section
 *     - 导出配置 ListItem
 *     - 导入配置 ListItem
 *   - 隐私说明 section
 *
 * SAF 文件选择器（`ActivityResultContracts.CreateDocument` / `OpenDocument`）由
 * [rememberLauncherForActivityResult] 持有——不需要任何存储权限。
 *
 * 状态机：
 * - Idle：按钮可点
 * - Working：按钮 disable + 居中显示进度圈 + 可见「取消」按钮（让用户随时中止大文件）
 * - Done：用 SnackbarHost 提示（"已导出 N 条"/"导入成功"），用户感知后回到 Idle
 *
 * **P1-3 修**：所有用户可见的临时状态（[pendingImportUri]、[step]）都用
 * [rememberSaveable] 而非 [androidx.compose.runtime.remember]，旋转屏幕 / 进程恢复
 * 后状态不会丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = ViewModelFactories.Settings)
    val state by viewModel.state.collectAsState()
    // SnackbarHostState 内部状态不需要 saveable——它只是消息队列，旋转屏幕时重建一个空队列即可
    val snackbarHostState = remember { SnackbarHostState() }
    // 选完文件后要二次确认的 URI（空 = 还没选 / 已被处理）；用 String 存比 Uri 简单
    var pendingImportUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingImportUri: Uri? = pendingImportUriString?.let(Uri::parse)

    // 导出：弹 SAF 文件创建器（application/json）
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    // 导入：弹 SAF 文件打开器
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pendingImportUriString = uri.toString()
        }

    // Done 状态 → 弹 Snackbar 然后回到 Idle
    LaunchedEffect(state) {
        val s = state
        if (s is SettingsUiState.Done) {
            snackbarHostState.showSnackbar(s.message)
            viewModel.acknowledge()
        }
    }

    // 导入模式选择 + 二次确认
    pendingImportUri?.let { uri ->
        ImportModeDialog(
            onDismiss = { pendingImportUriString = null },
            onConfirm = { mode ->
                pendingImportUriString = null
                viewModel.import(uri, mode)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            val enabled = state is SettingsUiState.Idle
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Text(
                        text = stringResource(R.string.settings_section_data),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_export)) },
                        supportingContent = { Text(stringResource(R.string.settings_export_subtitle)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier =
                            Modifier.clickable(enabled = enabled) {
                                val ts = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                exportLauncher.launch("cardrecord_backup_$ts.json")
                            },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_import)) },
                        supportingContent = { Text(stringResource(R.string.settings_import_subtitle)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier =
                            Modifier.clickable(enabled = enabled) {
                                importLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
                            },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.settings_privacy_note),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state is SettingsUiState.Working) {
                // 全屏半透遮挡，按钮 disable（列表里已处理）+ 居中进度 + 可见的「取消」按钮
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.cancel() }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 导入模式选择 + 二次确认。
 *
 * 用户视角：从备份恢复时，常见两种意图：
 * 1) "把现在的全删了换成备份" → REPLACE
 * 2) "保留现在的，再把备份加进来" → MERGE
 * 这里把两种放一起给用户选，REPLACE 还弹一次二次确认（破坏性操作）。
 *
 * **P1-3 修**：[step] 用 [rememberSaveable] + [Saver]，旋转屏幕不会丢。
 */
@Composable
private fun ImportModeDialog(
    onDismiss: () -> Unit,
    onConfirm: (ImportMode) -> Unit,
) {
    var step by rememberSaveable(saver = ImportModeStepStateSaver) {
        mutableStateOf(ImportModeStep.SELECT)
    }

    when (step) {
        ImportModeStep.SELECT ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.settings_import_dialog_title)) },
                text = { Text(stringResource(R.string.settings_import_dialog_message)) },
                confirmButton = {
                    TextButton(onClick = { step = ImportModeStep.CONFIRM_REPLACE }) {
                        Text(stringResource(R.string.settings_import_mode_replace))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = ImportModeStep.CONFIRM_MERGE }) {
                        Text(stringResource(R.string.settings_import_mode_merge))
                    }
                },
            )
        ImportModeStep.CONFIRM_REPLACE ->
            AlertDialog(
                onDismissRequest = { step = ImportModeStep.SELECT },
                title = { Text(stringResource(R.string.settings_import_replace_confirm_title)) },
                text = { Text(stringResource(R.string.settings_import_replace_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = { onConfirm(ImportMode.REPLACE) }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = ImportModeStep.SELECT }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        ImportModeStep.CONFIRM_MERGE ->
            AlertDialog(
                onDismissRequest = { step = ImportModeStep.SELECT },
                title = { Text(stringResource(R.string.settings_import_merge_confirm_title)) },
                text = { Text(stringResource(R.string.settings_import_merge_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = { onConfirm(ImportMode.MERGE) }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = ImportModeStep.SELECT }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
    }
}

private enum class ImportModeStep { SELECT, CONFIRM_REPLACE, CONFIRM_MERGE }

/**
 * [MutableState] 包装 [ImportModeStep] 的 Saver：把 enum 的 ordinal 存进 Bundle，
 * 重建时还原成 [MutableState] 供 Compose by 委托使用。
 *
 * enum 加新值时**不要**插在中间，否则 ordinal 会变 → 状态错位。要么追加在末尾，
 * 要么改用 enum.name 作为 key（字符串存 Bundle 更稳但体积大一点）。
 */
private val ImportModeStepStateSaver: Saver<MutableState<ImportModeStep>, Int> =
    Saver(
        save = { it.value.ordinal },
        restore = { ordinal ->
            mutableStateOf(ImportModeStep.entries.getOrNull(ordinal) ?: ImportModeStep.SELECT)
        },
    )
