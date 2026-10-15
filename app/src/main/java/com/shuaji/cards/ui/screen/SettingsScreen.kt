package com.shuaji.cards.ui.screen

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.ColorSource
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.backup.BackupPreview
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.reminder.AnnualFeeReminderNotifier
import com.shuaji.cards.ui.AppLanguage
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.LocalUiDateFormatter
import com.shuaji.cards.ui.component.ModernColorPicker
import com.shuaji.cards.ui.component.withoutBottom
import com.shuaji.cards.ui.theme.DefaultBrandPrimary
import com.shuaji.cards.ui.theme.parseSeedColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = ViewModelFactories.Settings)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val themeSettings by viewModel.themeSettings.collectAsStateWithLifecycle()
    val remindersEnabled by viewModel.annualFeeRemindersEnabled.collectAsStateWithLifecycle()
    val pendingReminderPermissionRequest by
        viewModel.pendingReminderPermissionRequest.collectAsStateWithLifecycle()
    val isWorking = state is SettingsUiState.Working
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var notificationsAllowed by remember {
        mutableStateOf(AnnualFeeReminderNotifier.canPostNotifications(context))
    }

    fun refreshNotificationPermission() {
        val allowed = AnnualFeeReminderNotifier.canPostNotifications(context)
        val changed = allowed != notificationsAllowed
        notificationsAllowed = allowed
        if (changed) {
            viewModel.onNotificationPermissionMaybeChanged()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshNotificationPermission()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshNotificationPermission()
        }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationPermission()
        }

    fun openAppNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelIntent =
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(
                            Settings.EXTRA_CHANNEL_ID,
                            AnnualFeeReminderNotifier.CHANNEL_ID,
                        )
                    }
                val manager = NotificationManagerCompat.from(context)
                val channel = manager.getNotificationChannel(AnnualFeeReminderNotifier.CHANNEL_ID)
                if (channel != null &&
                    channel.importance == android.app.NotificationManager.IMPORTANCE_NONE
                ) {
                    notificationSettingsLauncher.launch(channelIntent)
                    return
                }
            }
            val intent =
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            notificationSettingsLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            notificationSettingsLauncher.launch(fallback)
        }
    }

    fun requestNotificationPermission() {
        if (AnnualFeeReminderNotifier.canPostNotifications(context)) {
            refreshNotificationPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val activity = context as? Activity
                val showRationale =
                    activity?.shouldShowRequestPermissionRationale(
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == true
                if (viewModel.hasAskedNotificationPermission() && !showRationale) {
                    openAppNotificationSettings()
                } else {
                    viewModel.markAskedNotificationPermission()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                return
            }
        }
        openAppNotificationSettings()
    }

    fun setNotificationPermissionEnabled(enabled: Boolean) {
        if (!enabled) {
            openAppNotificationSettings()
            return
        }
        requestNotificationPermission()
    }

    fun setRemindersEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setAnnualFeeRemindersEnabled(false)
            return
        }
        // 先持久化「要提醒」：即使用户去系统设置授权时进程被杀，下次启动仍会重排。
        viewModel.setAnnualFeeRemindersEnabled(true)
        if (!AnnualFeeReminderNotifier.canPostNotifications(context)) {
            requestNotificationPermission()
        }
    }

    BackHandler(enabled = isWorking, onBack = viewModel::cancel)

    var showColorPicker by rememberSaveable { mutableStateOf(false) }

    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var languageChanges by remember { mutableIntStateOf(0) }
    val currentLanguage = remember(LocalConfiguration.current, languageChanges) { AppLanguage.current() }

    fun inspectSelection(uri: android.net.Uri?) {
        if (uri == null) return
        viewModel.dismissPendingImport()
        viewModel.inspectBackup(uri)
    }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    val importDirectoryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::inspectSelection)

    LaunchedEffect(state) {
        if (state is SettingsUiState.Done) {
            viewModel.acknowledge()
        }
    }

    LaunchedEffect(pendingReminderPermissionRequest) {
        if (!pendingReminderPermissionRequest) return@LaunchedEffect
        viewModel.acknowledgeReminderPermissionRequest()
        if (!AnnualFeeReminderNotifier.canPostNotifications(context)) {
            requestNotificationPermission()
        } else {
            refreshNotificationPermission()
        }
    }

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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding.withoutBottom())
                    .consumeWindowInsets(padding),
        ) {
            val enabled = state is SettingsUiState.Idle
            val working = state is SettingsUiState.Working
            LazyColumn(
                modifier = if (working) Modifier.semantics { hideFromAccessibility() } else Modifier,
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + padding.calculateBottomPadding()),
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

                item {
                    Text(
                        text = stringResource(R.string.settings_section_reminders),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_notification_permission)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_notification_permission_subtitle))
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = notificationsAllowed,
                                onCheckedChange = null,
                                enabled = enabled,
                            )
                        },
                        modifier =
                            Modifier.toggleable(
                                value = notificationsAllowed,
                                enabled = enabled,
                                role = Role.Switch,
                                onValueChange = { setNotificationPermissionEnabled(it) },
                            ),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_annual_fee_reminders)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_annual_fee_reminders_subtitle))
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = remindersEnabled,
                                onCheckedChange = null,
                                enabled = enabled,
                            )
                        },
                        modifier =
                            Modifier.toggleable(
                                value = remindersEnabled,
                                enabled = enabled,
                                role = Role.Switch,
                                onValueChange = { setRemindersEnabled(it) },
                            ),
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.settings_section_appearance),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                            Modifier.clickable(enabled = enabled && themeSettings != null, role = Role.Button) {
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
                            Modifier.clickable(enabled = enabled && themeSettings != null, role = Role.Button) {
                                val next =
                                    when (currentSource) {
                                        ColorSource.SYSTEM_DYNAMIC -> ColorSource.CUSTOM
                                        ColorSource.CUSTOM -> ColorSource.SYSTEM_DYNAMIC
                                    }
                                viewModel.setColorSource(next)
                            },
                    )
                }
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
                item {
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

    if (showColorPicker) {
        themeSettings?.let { loaded ->
            val savedColor = parseSeedColor(loaded.seedColorHex) ?: DefaultBrandPrimary
            var pickedArgb by rememberSaveable(savedColor) { mutableIntStateOf(savedColor.toArgb()) }
            val pickerInitialColor = remember(savedColor) { Color(pickedArgb) }
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
                            initialColor = pickerInitialColor,
                            onColorSelected = { pickedArgb = it.toArgb() },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.setSeedColorHex("#%06X".format(0xFFFFFF and pickedArgb))
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

    if (showLanguageDialog) {
        val current = currentLanguage
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
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
                                            if (lang != current) {
                                                AppLanguage.apply(lang)
                                                languageChanges++
                                            }
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

@Composable
private fun ImportModeDialog(
    info: BackupPreview,
    onDismiss: () -> Unit,
    onConfirm: (ImportMode) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(ImportModeStep.SELECT) }

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

@Composable
private fun confirmMessage(
    @androidx.annotation.StringRes templateRes: Int,
    info: BackupPreview,
): String {
    val dates = LocalUiDateFormatter.current
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
            stringResource(R.string.settings_backup_time_label, dates.dateTime(it))
        } ?: ""
    return listOf(template, timeLine)
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n")
}
