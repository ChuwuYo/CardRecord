package com.shuaji.cards.ui.screen

import android.content.Intent
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.ui.ViewModelFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * **P1 修**：所有用户可见的临时状态（[pendingImportUriString]、[ImportModeStep]）都用
 * [rememberSaveable] 而非 [androidx.compose.runtime.remember]，旋转屏幕 /
 * 进程恢复后状态不会丢。
 *
 * **P2 修**：导入文件后立刻用 `DocumentsContract.Document.COLUMN_LAST_MODIFIED` 拿文件
 * 最后修改时间，在二次确认对话框里显示给用户（「备份时间：2024-05-12 18:23」）——
 * 比 JSON 内的 `exportedAtMillis` 更权威（是文件实际写入时间，不是序列化时刻）。
 *
 * **P1-2 修**：`readBackupFileInfo` 是 suspend，必须在 `Dispatchers.IO` 跑（不能阻塞主线程），
 * 只解析顶层 metadata（`cards.length` / `folders.length` / `transactions.length`）——
 * 不反序列化整棵树。
 *
 * **P1-4 修**：导入文件 URI 调 `takePersistableUriPermission` 持久化；不导入时
 * `releasePersistableUriPermission` 释放——避免 grant slot 永久占用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = ViewModelFactories.Settings)
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 选完文件后要二次确认的 URI（空 = 还没选 / 已被处理）；用 String 存比 Uri 简单
    var pendingImportUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingImportUri: Uri? = pendingImportUriString?.let(Uri::parse)

    // 解析文件后获取的备份摘要（行数 + 最后修改时间）—— 二次确认时显示
    var pendingImportInfo by rememberSaveable(
        saver = BackupFileInfoStateSaver,
    ) { mutableStateOf<BackupFileInfo?>(null) }

    // P1-5 修：记录 takePersistableUriPermission 是否成功，传给 ImportModeDialog
    // 做 UI 警告。用 rememberSaveable 保证旋转屏幕后状态不丢。
    var tookPersistable by rememberSaveable { mutableStateOf(true) }

    // 导出：弹 SAF 文件创建器（application/json）
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    // 导入：弹 SAF 文件打开器
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // P1-4 修：持久化读权限——OpenDocument 拿到的 URI 进程级读权限在
                // 进程被 LMK 杀掉后会失效。持久化后 LMK 杀进程后用户回来仍能导入
                val tookOk =
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        true
                    }.getOrDefault(false)
                tookPersistable = tookOk // P1-5 修：把状态写进 rememberSaveable，传给对话框
                pendingImportUriString = uri.toString()
                pendingImportInfo = null // 清掉旧的（如果有）
                // P1-2 修：后台 IO 线程解析；只读顶层 metadata，不反序列化整棵 JSON 树
                coroutineScope.launch {
                    val info =
                        withContext(Dispatchers.IO) {
                            readBackupFileInfo(context.contentResolver, uri)
                        }
                    pendingImportInfo = info
                    // P1-5 修：take 失败时写 log（UI 警告在 ImportModeDialog 里显示）
                    if (!tookOk) {
                        android.util.Log.w(
                            "SettingsScreen",
                            "takePersistableUriPermission 失败，导入 URI ${uri} 在进程重启后将不可访问",
                        )
                    }
                }
            }
        }

    // Done 状态变化 → acknowledge（解 ack 只清 state；SharedFlow 事件已发出去了）
    LaunchedEffect(state) {
        if (state is SettingsUiState.Done) {
            viewModel.acknowledge()
        }
    }

    // 导入模式选择 + 二次确认
    // P1-5 修：把 tookPersistable 状态传进对话框，让用户在二次确认时能看到
    // 「⚠️ 文件访问权限未持久化」警告——之前只写 log，用户完全无感知。
    pendingImportUri?.let { uri ->
        ImportModeDialog(
            info = pendingImportInfo,
            tookPersistable = tookPersistable,
            onDismiss = {
                // P1-4 修：用户取消时释放持久化读权限——避免 grant slot 永久占用
                pendingImportUriString?.let { uriStr ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(uriStr),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
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

                // ────── 外观设置 ──────
                item {
                    Text(
                        text = stringResource(R.string.settings_section_appearance),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    val themeSettings by viewModel.themeSettings.collectAsState(initial = null)
                    val currentMode = themeSettings?.themeMode ?: ThemeMode.SYSTEM
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_theme_mode)) },
                        supportingContent = {
                            Text(
                                when (currentMode) {
                                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_mode_system)
                                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_mode_light)
                                    ThemeMode.DARK -> stringResource(R.string.settings_theme_mode_dark)
                                }
                            )
                        },
                        modifier = Modifier.clickable(enabled = enabled) {
                            // 循环切换：SYSTEM → LIGHT → DARK → SYSTEM
                            val next =
                                when (currentMode) {
                                    ThemeMode.SYSTEM -> ThemeMode.LIGHT
                                    ThemeMode.LIGHT -> ThemeMode.DARK
                                    ThemeMode.DARK -> ThemeMode.SYSTEM
                                }
                            viewModel.setThemeMode(next)
                        },
                    )
                }
                item {
                    val themeSettings by viewModel.themeSettings.collectAsState(initial = null)
                    val dynamicEnabled = themeSettings?.useDynamicColor ?: true
                    val sdkOk = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.settings_dynamic_color_subtitle)) },
                        trailingContent = {
                            Switch(
                                checked = dynamicEnabled,
                                onCheckedChange = { viewModel.setUseDynamicColor(it) },
                                enabled = sdkOk && enabled,
                            )
                        },
                        modifier = Modifier.clickable(enabled = sdkOk && enabled) {
                            viewModel.setUseDynamicColor(!dynamicEnabled)
                        },
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
 * 用 `DocumentsContract.Document.COLUMN_LAST_MODIFIED` + 解析 JSON 顶层的
 * 「卡片/文件夹/流水数」算出来的备份文件元数据。
 *
 * - [cardCount] / [folderCount] / [transactionCount] 用于二次确认带计数
 * - [lastModifiedMillis] 用于「备份时间：xxx」展示
 * - [imageUriUserCount] 跨设备恢复时给用户「N 张卡需重新上传」警告（P2-6 修）
 *
 * 任一字段缺失/解析失败就降级为 `null`，UI 显示「未知」。
 */
@Serializable
data class BackupFileInfo(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
    val imageUriUserCount: Int,
    val lastModifiedMillis: Long?,
)

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
 * 用 [android.content.ContentResolver.query] 拿 `DocumentsContract.Document.COLUMN_LAST_MODIFIED` /
 * `DocumentsContract.Document.COLUMN_DISPLAY_NAME`；同时用 `Json.parseToJsonElement` **只**
 * 读顶层 cards / folders / transactions 三个数组的 `size`——**不**反序列化整棵 JSON 树。
 *
 * **P1-2 修**：原来 `decodeFromString<BackupBundle>(text)` 把整段 JSON 反序列化成完整对象
 * 树（卡 / 文件夹 / 流水的每个字段全部构造），对 100k+ 张卡的备份会主线程卡顿 + 2× 内存。
 * 改用 `parseToJsonElement` 只读顶层 → 不构造子树 → 内存稳定。
 *
 * **`runCatching` 兜底**：文件 IO / 解析失败都返回 `null`，UI 降级「未知」文案。
 */
private suspend fun readBackupFileInfo(
    resolver: android.content.ContentResolver,
    uri: Uri,
): BackupFileInfo? {
    var lastModified: Long? = null
    // DocumentsContract.Document.COLUMN_LAST_MODIFIED（API 19+）而不是
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
            val root = BackupFileInfoJson.parseToJsonElement(text).jsonObject

            fun arraySize(key: String): Int =
                runCatching { root[key]?.jsonArray?.size ?: 0 }.getOrDefault(0)

            val cardCount = arraySize("cards")
            val folderCount = arraySize("folders")
            val transactionCount = arraySize("transactions")

            // imageUriUserCount = cards 里 imageSourceType == USER 的数量
            // ——不用反序列化 CardEntity，直接读 jsonObject
            var userCount = 0
            runCatching {
                val cardsArr = root["cards"]?.jsonArray
                if (cardsArr != null) {
                    for (cardEl in cardsArr) {
                        val src = cardEl.jsonObject["imageSourceType"]?.jsonPrimitive?.content
                        if (src == ImageSourceType.USER.name) userCount++
                    }
                }
            }
            BackupFileInfo(
                cardCount = cardCount,
                folderCount = folderCount,
                transactionCount = transactionCount,
                imageUriUserCount = userCount,
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
 * **P1 修**：REPLACE/MERGE 二次确认对话框都把 [BackupFileInfo]（行数 + 备份时间 +
 * imageUriUserCount）拼进确认文案——「此备份包含 N 张卡 / M 个文件夹 / K 笔流水」+
 * 「其中 N 张卡的卡面需要重新上传」（跨设备恢复时）+ 「备份时间：xxx」，
 * 用户能看清要操作的数据规模 + 风险再点确认。
 *
 * **P1 修**：[step] 用 [rememberSaveable] + [Saver]，旋转屏幕不会丢。
 */
@Composable
private fun ImportModeDialog(
    info: BackupFileInfo?,
    tookPersistable: Boolean,
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
                text = { Text(confirmMessage(R.string.settings_import_replace_confirm_message_with_count, info, tookPersistable)) },
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
                text = { Text(confirmMessage(R.string.settings_import_merge_confirm_message_with_count, info, tookPersistable)) },
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
 * 把「此备份包含 N 张卡 / M 个文件夹 / K 笔流水」+ 「其中 N 张卡需重新上传」+
 * 「备份时间：xxx」+ 「⚠️ 文件访问权限未持久化」拼起来。
 * 解析失败时 [info] = null，UI 显示「未知」降级。
 */
@Composable
private fun confirmMessage(
    @androidx.annotation.StringRes templateRes: Int,
    info: BackupFileInfo?,
    tookPersistable: Boolean,
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
    val imageWarningLine =
        info?.takeIf { it.imageUriUserCount > 0 }?.let {
            stringResource(R.string.settings_dialog_image_warning, it.imageUriUserCount)
        } ?: ""
    // P1-5 修：takePersistableUriPermission 失败时给用户显式警告——之前只写 log，
    // 当 imageUriUserCount=0 时用户完全不知道持久化失败了。
    val persistWarningLine =
        if (tookPersistable) "" else stringResource(R.string.settings_import_persist_warning)
    return listOf(template, timeLine, imageWarningLine, persistWarningLine)
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n")
}