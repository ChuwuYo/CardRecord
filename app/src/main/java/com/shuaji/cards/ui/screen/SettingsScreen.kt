package com.shuaji.cards.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.ColorSource
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.backup.BackupPreview
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.ui.AppLanguage
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.ModernColorPicker
import com.shuaji.cards.ui.theme.DefaultBrandPrimary
import com.shuaji.cards.ui.theme.parseSeedColor
import kotlinx.coroutines.launch
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
 * SAF 目录选择器（`ActivityResultContracts.OpenDocumentTree`）由
 * [rememberLauncherForActivityResult] 持有——不需要任何存储权限。
 *
 * 状态机：
 * - Idle：按钮可点
 * - Working：按钮 disable + 居中显示进度圈 + 可见「取消」按钮（让用户随时中止大文件）
 * - Done：emit 到 [com.shuaji.cards.data.AppContainer.settingsEvents]，由
 *   `ShuajiApp` 顶层全局 `SnackbarHost` 消费（用户在任何页面都能看到）。
 *
 * SnackbarHost 由 `ShuajiApp` 顶层持有，本页只发布操作结果。
 *
 * 导入目录先由 [SettingsViewModel.inspectBackup] 走与正式导入相同的受限解码；
 * 只有结构校验成功后才显示模式确认对话框及记录数量。
 *
 * 备份是一次性读取，不申请持久化 URI 权限。进程若在确认期间结束，用户重新选择目录即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = ViewModelFactories.Settings)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val themeSettings by viewModel.themeSettings.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    val isWorking = state is SettingsUiState.Working

    BackHandler(enabled = isWorking, onBack = viewModel::cancel)

    // 自定义颜色取色器对话框显示状态
    var showColorPicker by rememberSaveable { mutableStateOf(false) }

    // 语言选择对话框显示状态
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }

    fun inspectSelection(uri: android.net.Uri?) {
        if (uri == null) return
        viewModel.dismissPendingImport()
        coroutineScope.launch { viewModel.inspectBackup(uri) }
    }

    // 导出：选择父目录后，由仓库新建备份目录；仅有图片引用时才创建 card_images。
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    // 导入只接受由本应用导出的完整备份目录。
    val importDirectoryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::inspectSelection)

    // Done 状态变化 → acknowledge；完成消息由进程级一次性事件队列独立交付。
    LaunchedEffect(state) {
        if (state is SettingsUiState.Done) {
            viewModel.acknowledge()
        }
    }

    // 导入模式选择 + 二次确认
    pendingImport?.let { pending ->
        ImportModeDialog(
            info = pending.info,
            onDismiss = viewModel::dismissPendingImport,
            onConfirm = viewModel::importPending,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (isWorking) viewModel.cancel() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        // SnackbarHost 由 ShuajiApp 顶层持有。
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            val enabled = state is SettingsUiState.Idle
            val working = state is SettingsUiState.Working
            LazyColumn(
                modifier = if (working) Modifier.semantics { hideFromAccessibility() } else Modifier,
                contentPadding = PaddingValues(vertical = 8.dp),
                userScrollEnabled = !working,
            ) {
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
                                exportLauncher.launch(null)
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
                                importDirectoryLauncher.launch(null)
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
                // 主题模式：跟随系统 / 浅色 / 深色
                item {
                    val currentMode = themeSettings?.themeMode ?: ThemeMode.SYSTEM
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Default.BrightnessMedium,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.settings_theme_mode)) },
                        supportingContent = {
                            if (themeSettings == null) {
                                Text(stringResource(R.string.common_loading))
                            } else {
                                Text(
                                    when (currentMode) {
                                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_mode_system)
                                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_mode_light)
                                        ThemeMode.DARK -> stringResource(R.string.settings_theme_mode_dark)
                                    },
                                )
                            }
                        },
                        modifier =
                            Modifier.clickable(enabled = enabled && themeSettings != null) {
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
                // 颜色来源：系统动态 / 自定义
                item {
                    val currentSource = themeSettings?.colorSource ?: ColorSource.SYSTEM_DYNAMIC
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.settings_color_source)) },
                        supportingContent = {
                            if (themeSettings == null) {
                                Text(stringResource(R.string.common_loading))
                            } else {
                                Text(
                                    when (currentSource) {
                                        ColorSource.SYSTEM_DYNAMIC ->
                                            stringResource(R.string.settings_color_source_system)
                                        ColorSource.CUSTOM ->
                                            stringResource(R.string.settings_color_source_custom)
                                    },
                                )
                            }
                        },
                        modifier =
                            Modifier.clickable(enabled = enabled && themeSettings != null) {
                                // 二选一切换：SYSTEM_DYNAMIC ↔ CUSTOM
                                val next =
                                    when (currentSource) {
                                        ColorSource.SYSTEM_DYNAMIC -> ColorSource.CUSTOM
                                        ColorSource.CUSTOM -> ColorSource.SYSTEM_DYNAMIC
                                    }
                                viewModel.setColorSource(next)
                            },
                    )
                }
                // 自定义取色器入口（CUSTOM 时显示）
                item {
                    if (themeSettings?.colorSource == ColorSource.CUSTOM) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Default.Colorize,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_custom_color)) },
                            supportingContent = {
                                themeSettings?.seedColorHex?.let {
                                    Text(it)
                                } ?: Text(stringResource(R.string.settings_custom_color_none))
                            },
                            modifier =
                                Modifier.clickable(enabled = enabled) {
                                    showColorPicker = true
                                },
                        )
                    }
                }
                // 语言：应用内切换（AppCompat per-app language，列表由 AppLanguage.entries 驱动）
                item {
                    val currentLanguage = AppLanguage.current()
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Default.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.settings_language)) },
                        supportingContent = { Text(stringResource(currentLanguage.labelRes)) },
                        modifier =
                            Modifier.clickable(enabled = enabled) {
                                showLanguageDialog = true
                            },
                    )
                }
            }

            if (state is SettingsUiState.Working) {
                // 全屏半透遮挡，按钮 disable（列表里已处理）+ 居中进度 + 可见的「取消」按钮
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)),
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

    // 自定义颜色取色器对话框
    if (showColorPicker) {
        // 等设置加载完成再建取色器：themeSettings 首帧为 null，若此时就建 picker，
        // initialColor 会被定成默认色且之后无法回填——必须拿到已保存的 seedColorHex 再建，
        // 这样重新打开时取色器停在「当前已选颜色」而非默认色。
        themeSettings?.let { loaded ->
            val savedColor = parseSeedColor(loaded.seedColorHex) ?: DefaultBrandPrimary
            var pickedColor by remember(savedColor) { mutableStateOf(savedColor) }
            AlertDialog(
                onDismissRequest = { showColorPicker = false },
                title = { Text(stringResource(R.string.settings_custom_color)) },
                text = {
                    Box(
                        modifier =
                            Modifier
                                .heightIn(max = 440.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        ModernColorPicker(
                            initialColor = savedColor,
                            onColorSelected = { pickedColor = it },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.setSeedColorHex("#%06X".format(0xFFFFFF and pickedColor.toArgb()))
                            showColorPicker = false
                        },
                    ) {
                        Text(stringResource(R.string.common_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showColorPicker = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }

    // 语言选择对话框：选项由 AppLanguage.entries 驱动，新增语言无需改这里
    if (showLanguageDialog) {
        val current = AppLanguage.current()
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
                // 单选对话框用透明、带 selectable 语义的 Row（MD3 single-choice dialog 模式），
                // 而不是 ListItem——ListItem 的容器色是 colorScheme.surface（深色主题下近黑），
                // 嵌在 AlertDialog 的 surfaceContainerHigh 容器里会显示成一块更黑的卡片，不协调。
                // Row 背景透明，继承对话框表面色；selectable 提供单选无障碍语义。
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = lang == current,
                                        role = Role.RadioButton,
                                        onClick = {
                                            showLanguageDialog = false
                                            // AppCompat 会持久化并重建 Activity 以应用新语言
                                            if (lang != current) AppLanguage.apply(lang)
                                        },
                                    ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = lang == current,
                                onClick = null,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = stringResource(lang.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * 先选择覆盖或追加，再显示记录数量与备份时间；覆盖在提交前必须二次确认。
 * [step] 可保存，避免配置变化绕过确认步骤。
 */
@Composable
private fun ImportModeDialog(
    info: BackupPreview,
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
                text = {
                    Text(confirmMessage(R.string.settings_import_replace_confirm_message_with_count, info))
                },
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
                text = {
                    Text(confirmMessage(R.string.settings_import_merge_confirm_message_with_count, info))
                },
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
 * 把记录数量与备份时间拼成确认文案。
 * 对话框只接收仓库完整校验后的摘要，不存在以 0 冒充解析失败的降级路径。
 */
@Composable
private fun confirmMessage(
    @androidx.annotation.StringRes templateRes: Int,
    info: BackupPreview,
): String {
    val template =
        stringResource(
            templateRes,
            pluralStringResource(R.plurals.backup_count_cards, info.cardCount, info.cardCount),
            pluralStringResource(R.plurals.backup_count_folders, info.folderCount, info.folderCount),
            pluralStringResource(
                R.plurals.backup_count_transactions,
                info.transactionCount,
                info.transactionCount,
            ),
        )
    val timeLine =
        info.lastModifiedMillis?.let {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            stringResource(R.string.settings_backup_time_label, fmt.format(Date(it)))
        } ?: ""
    return listOf(template, timeLine)
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n")
}
