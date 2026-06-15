package com.shuaji.cards.ui.screen

import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.ui.ViewModelFactories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * - Done：emit 到 [com.shuaji.cards.data.AppContainer.settingsEvents]，由
 *   `ShuajiApp` 顶层全局 `SnackbarHost` 消费（用户在任何页面都能看到）。
 *
 * **P1 修**：本页面**不**再自带 SnackbarHost——原来 SettingsScreen 自己持有一个
 * `SnackbarHostState`，结果就是用户在 SettingsScreen 点完「导出」→「已导出 N 条」
 * 提示弹出来，但只要他跳到 Home 或者锁屏 / 通知就看不到——消息随页面销毁丢了。
 * 现在改成 ViewModel emit 到 AppContainer.settingsEvents，顶层订阅，在任意
 * 页面 / 锁屏 / 通知都能消费。
 *
 * **P1 修**：所有用户可见的临时状态（[pendingImportUri]、[step]）都用
 * [rememberSaveable] 而非 [androidx.compose.runtime.remember]，旋转屏幕 /
 * 进程恢复后状态不会丢。
 *
 * **P2 修**：导入文件后立刻用 `OpenableColumns.LAST_MODIFIED` 拿文件最后修改时间，
 * 在二次确认对话框里显示给用户（「备份时间：2024-05-12 18:23」）——比 JSON 内的
 * `exportedAtMillis` 更权威（是文件实际写入时间，不是序列化时刻）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = ViewModelFactories.Settings)
    val state by viewModel.state.collectAsState()

    // 选完文件后要二次确认的 URI（空 = 还没选 / 已被处理）；用 String 存比 Uri 简单
    var pendingImportUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingImportUri: Uri? = pendingImportUriString?.let(Uri::parse)

    // 解析文件后获取的备份摘要（行数 + 最后修改时间）—— 二次确认时显示
    var pendingImportInfo by rememberSaveable(
        saver = BackupFileInfoStateSaver,
    ) { mutableStateOf<BackupFileInfo?>(null) }
    val context = LocalContext.current

    // 导出：弹 SAF 文件创建器（application/json）
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    // 导入：弹 SAF 文件打开器
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingImportUriString = uri.toString()
                // 选完文件立刻解析 LAST_MODIFIED + 实际行数（用一次性 in-memory Json.parse）
                pendingImportInfo = readBackupFileInfo(context.contentResolver, uri)
            }
        }

    // Done 状态变化 → acknowledge（解 ack 只清 state；SharedFlow 事件已发出去了）
    LaunchedEffect(state) {
        if (state is SettingsUiState.Done) {
            viewModel.acknowledge()
        }
    }

    // 导入模式选择 + 二次确认
    pendingImportUri?.let { uri ->
        ImportModeDialog(
            info = pendingImportInfo,
            onDismiss = {
                pendingImportUriString = null
                pendingImportInfo = null
            },
            onConfirm = { mode ->
                pendingImportUriString = null
                pendingImportInfo = null
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
        // P1 修：snackbarHost 改顶层（ShuajiApp）——本页面不再带。
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
 * 用 `OpenableColumns.LAST_MODIFIED` + 解析 JSON 顶层的「卡片/文件夹/流水数」
 * 算出来的备份文件元数据。
 *
 * - [cardCount] / [folderCount] / [transactionCount] 用于二次确认带计数
 * - [lastModifiedMillis] 用于「备份时间：xxx」展示
 *
 * 任一字段缺失/解析失败就降级为 `null`，UI 显示「未知」。
 */
@Serializable
data class BackupFileInfo(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
    val lastModifiedMillis: Long?,
) {
    val isEmpty: Boolean get() = cardCount == 0 && folderCount == 0 && transactionCount == 0
}

private val BackupFileInfoStateSaver: Saver<MutableState<BackupFileInfo?>, String> =
    Saver(
        save = { it.value?.let { v -> Json.encodeToString(BackupFileInfo.serializer(), v) } ?: "" },
        restore = { json ->
            mutableStateOf<BackupFileInfo?>(
                if (json.isEmpty()) null
                else runCatching { Json.decodeFromString(BackupFileInfo.serializer(), json) }.getOrNull(),
            )
        },
    )

/**
 * 读 SAF 选中的备份文件元数据。
 *
 * 用 [android.content.ContentResolver.query] 拿 `OpenableColumns.LAST_MODIFIED` /
 * `OpenableColumns.SIZE`；同时用 in-memory `kotlinx.serialization.Json` 解析
 * 顶层 cards / folders / transactions 长度——只解析长度、**不**把整段 JSON
 * 走 dao 入库（那是用户点确认后的活）。
 *
 * 任何失败都返回 `null`——`pendingImportInfo` 是可空的，UI 会用「未知」降级文案。
 */
private fun readBackupFileInfo(
    resolver: android.content.ContentResolver,
    uri: Uri,
): BackupFileInfo? {
    var lastModified: Long? = null
    // 用 DocumentsContract.Document.COLUMN_LAST_MODIFIED（API 19+）而不是
    // OpenableColumns.LAST_MODIFIED（API 29+）——minSdk=26，旧的常量在 API 26-28 查不到。
    resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idx >= 0 && !c.isNull(idx)) {
                lastModified = c.getLong(idx)
            }
        }
    }
    return runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            val bundle = BackupFileInfoJson.decodeFromString(
                com.shuaji.cards.data.backup.BackupBundle.serializer(),
                text,
            )
            BackupFileInfo(
                cardCount = bundle.cards.size,
                folderCount = bundle.folders.size,
                transactionCount = bundle.transactions.size,
                lastModifiedMillis = lastModified,
            )
        }
    }.getOrNull()
}

/** 顶层单例 [Json]，避免 `readBackupFileInfo` 每次调用都 new 一个（warning 修）。 */
private val BackupFileInfoJson =
    Json { ignoreUnknownKeys = true }

/**
 * 导入模式选择 + 二次确认。
 *
 * 用户视角：从备份恢复时，常见两种意图：
 * 1) "把现在的全删了换成备份" → REPLACE
 * 2) "保留现在的，再把备份加进来" → MERGE
 * 这里把两种放一起给用户选，REPLACE 还弹一次二次确认（破坏性操作）。
 *
 * **P1 修**：REPLACE/MERGE 二次确认对话框都把 [BackupFileInfo]（行数 + 备份时间）拼进
 * 确认文案——「此备份包含 N 张卡 / M 个文件夹 / K 笔流水（备份时间：xxx）」，
 * 用户能看清要操作的数据规模再点确认。
 *
 * **P1 修**：[step] 用 [rememberSaveable] + [Saver]，旋转屏幕不会丢。
 */
@Composable
private fun ImportModeDialog(
    info: BackupFileInfo?,
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
                text = { Text(confirmMessage(R.string.settings_import_replace_confirm_message_with_count, info)) },
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
                text = { Text(confirmMessage(R.string.settings_import_merge_confirm_message_with_count, info)) },
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

private val ImportModeStepStateSaver: Saver<MutableState<ImportModeStep>, Int> =
    Saver(
        save = { it.value.ordinal },
        restore = { ordinal ->
            mutableStateOf(ImportModeStep.entries.getOrNull(ordinal) ?: ImportModeStep.SELECT)
        },
    )

/**
 * 把「此备份包含 N 张卡 / M 个文件夹 / K 笔流水」+ 「备份时间：xxx」拼起来。
 * 解析失败时 [info] = null，UI 显示「未知」降级。
 */
@Composable
private fun confirmMessage(
    @androidx.annotation.StringRes templateRes: Int,
    info: BackupFileInfo?,
): String {
    val template =
        if (info != null) {
            stringResource(
                templateRes,
                info.cardCount,
                info.folderCount,
                info.transactionCount,
            )
        } else {
            // 解析失败时去掉 % 占位——回退到"包含若干记录"
            // 模板里 %1$d / %2$d / %3$d 必须有，0/0/0 表示未知
            stringResource(templateRes, 0, 0, 0)
        }
    val timeLine =
        info?.lastModifiedMillis?.let {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            stringResource(R.string.settings_backup_time_label, fmt.format(Date(it)))
        } ?: ""
    return if (timeLine.isNotEmpty()) "$template\n$timeLine" else template
}
