package com.shuaji.cards.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.ModernColorPicker
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(
    cardId: Long?,
    onBack: () -> Unit,
) {
    val viewModel: CardEditViewModel = viewModel(factory = ViewModelFactories.Edit)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showImageImportError by remember { mutableStateOf(false) }
    val networkPickerPresentation =
        resolveCardNetworkPickerPresentation(state.imageSourceType, state.imageProviderKey)

    val closeWithoutSaving = viewModel::closeWithoutSaving

    LaunchedEffect(cardId) {
        viewModel.initialize(cardId)
    }
    LaunchedEffect(state.saveResult) {
        when (state.saveResult) {
            is CardEditSaveResult.Saved -> onBack()
            CardEditSaveResult.Failed -> snackbarHostState.showSnackbar(context.getString(R.string.edit_save_failed))
            else -> Unit
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CardEditEvent.ImageImportFailed -> showImageImportError = true
                CardEditEvent.CloseReady -> onBack()
            }
        }
    }
    BackHandler(onBack = closeWithoutSaving)

    var dateDialogTarget by remember { mutableStateOf<DateField?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    val colorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Photo Picker 只提供一次性输入 URI；选中后立即复制进应用私有目录。
    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            if (uri != null) {
                viewModel.selectUserImage(uri.toString())
            }
        }

    // 集中预解析字符串，避免在 Composable 子作用域里重复调用 stringResource
    val titleText =
        stringResource(if (cardId == null) R.string.edit_title_new else R.string.edit_title_edit)
    val backCd = stringResource(R.string.common_back)
    val saveText = stringResource(R.string.common_save)
    val imageSection = stringResource(R.string.edit_section_image)
    val networkSection = stringResource(R.string.edit_section_choose_network)
    val imagePickHint = stringResource(R.string.edit_image_pick_hint)
    val clearImageCd = stringResource(R.string.edit_image_clear)
    val imageCd = stringResource(R.string.card_image_content_description)
    val orientationSection = stringResource(R.string.edit_section_orientation)
    val folderSection = stringResource(R.string.edit_section_folder)
    val colorSection = stringResource(R.string.edit_section_theme_color)
    val unfiledText = stringResource(R.string.edit_folder_unfiled)
    val unsetText = stringResource(R.string.edit_date_unset)
    val clearText = stringResource(R.string.common_clear)
    val selectText = stringResource(R.string.common_select)
    val colorPickerTitle = stringResource(R.string.edit_color_picker_title)
    val doneText = stringResource(R.string.common_done)
    val saveBtnText =
        stringResource(if (cardId == null) R.string.edit_save_new else R.string.edit_save_existing)
    val confirmText = stringResource(R.string.common_confirm)
    val cancelText = stringResource(R.string.common_cancel)
    val nameLabel = stringResource(R.string.edit_field_name)
    val nameHint = stringResource(R.string.edit_field_name_hint)
    val bankLabel = stringResource(R.string.edit_field_bank)
    val bankHint = stringResource(R.string.edit_field_bank_hint)
    val numberLabel = stringResource(R.string.edit_field_number)
    val numberHint = stringResource(R.string.edit_field_number_hint)
    val requiredLabel = stringResource(R.string.edit_field_required)
    val noteLabel = stringResource(R.string.edit_field_note)
    val cardTypeSection = stringResource(R.string.edit_card_type)
    val cardTypeUnspecified = stringResource(R.string.card_type_unspecified)
    val cardTypeDebit = stringResource(R.string.card_type_debit)
    val cardTypeCredit = stringResource(R.string.card_type_credit)
    val statementDayLabel = stringResource(R.string.edit_statement_day)
    val repaymentDayLabel = stringResource(R.string.edit_repayment_day)
    val dayOfMonthHint = stringResource(R.string.edit_day_of_month_hint)
    val dayOutOfRange = stringResource(R.string.edit_day_out_of_range)
    val validUntilLabel = stringResource(R.string.edit_date_valid_until)
    val nextDueLabel = stringResource(R.string.edit_date_next_due)
    val nextDueError = stringResource(R.string.edit_next_due_must_be_future)
    val providerLabel = stringResource(R.string.edit_image_provider)
    val userLabel = stringResource(R.string.edit_image_user)
    val noneLabel = stringResource(R.string.edit_image_none)
    val landscapeLabel = stringResource(R.string.edit_orientation_landscape)
    val portraitLabel = stringResource(R.string.edit_orientation_portrait)

    if (cardId != null && state.loadState != CardEditLoadState.READY) {
        val isLoading =
            state.loadState == CardEditLoadState.NEW || state.loadState == CardEditLoadState.LOADING
        CardEditLoadScaffold(
            title = titleText,
            backContentDescription = backCd,
            isLoading = isLoading,
            message =
                when (state.loadState) {
                    CardEditLoadState.MISSING -> stringResource(R.string.card_missing)
                    CardEditLoadState.FAILED -> stringResource(R.string.edit_load_failed)
                    else -> null
                },
            retryLabel = stringResource(R.string.common_retry),
            onBack = closeWithoutSaving,
            onRetry = if (state.loadState == CardEditLoadState.FAILED) ({ viewModel.load(cardId) }) else null,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = closeWithoutSaving,
                        enabled = !state.isSaving && state.saveResult !is CardEditSaveResult.Saved && !state.isClosing,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backCd)
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }, enabled = state.canSave) {
                        Text(saveText)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                cardTypeSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardTypeSelector(
                current = state.cardType,
                unspecifiedLabel = cardTypeUnspecified,
                debitLabel = cardTypeDebit,
                creditLabel = cardTypeCredit,
                onSelect = { type -> viewModel.update { it.copy(cardType = type) } },
            )

            // ── 卡面来源 ──
            Text(
                imageSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImageSourceSelector(
                current = state.imageSourceType,
                providerLabel = providerLabel,
                userLabel = userLabel,
                noneLabel = noneLabel,
                onSelect = viewModel::selectImageSource,
            )

            if (networkPickerPresentation.visible) {
                Text(
                    networkSection,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardNetworkPicker(
                    selectedKey = networkPickerPresentation.selectedKey,
                    onSelect = viewModel::selectNetwork,
                )
            }

            if (state.imageSourceType == ImageSourceType.USER) {
                // 预览比例与卡片朝向一致，ContentScale.Fit 保留完整图片。
                val cardAspect = state.cardOrientation.aspectRatio
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(cardAspect)
                            .clickable(
                                enabled = !state.isSaving && !state.isClosing && !state.isImportingImage,
                            ) {
                                imagePicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isImportingImage) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                Text(
                                    text = stringResource(R.string.edit_image_importing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (state.imageUri != null) {
                            coil3.compose.AsyncImage(
                                model = state.imageUri,
                                contentDescription = imageCd,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.medium),
                                contentScale = ContentScale.Fit,
                            )
                            IconButton(
                                onClick = {
                                    viewModel.clearUserImage()
                                },
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                            ) {
                                Icon(
                                    Icons.Default.LayersClear,
                                    contentDescription = clearImageCd,
                                    tint = Color.White,
                                    modifier =
                                        Modifier
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            .padding(4.dp),
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    imagePickHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── 朝向选择 ──
            Text(
                orientationSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OrientationSelector(
                current = state.cardOrientation,
                landscapeLabel = landscapeLabel,
                portraitLabel = portraitLabel,
                onSelect = { o -> viewModel.update { it.copy(cardOrientation = o) } },
            )

            // ── 文件夹 ──
            if (folders.isNotEmpty()) {
                Text(
                    folderSection,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FolderPicker(
                    folders = folders,
                    currentId = state.folderId,
                    unfiledLabel = unfiledText,
                    onSelect = { id -> viewModel.update { it.copy(folderId = id) } },
                )
            }

            // ── 主题色（自定义调色板） ──
            Text(
                colorSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showColorPicker = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(state.colorArgb))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    ),
                        )
                        Text(
                            text = "#%06X".format(state.colorArgb.toLong() and 0xFFFFFFL),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 卡名 / 发卡行 / 卡号 ──
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(nameLabel) },
                placeholder = { Text(nameHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.bank,
                onValueChange = { v -> viewModel.update { it.copy(bank = v) } },
                label = { Text(bankLabel) },
                placeholder = { Text(bankHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.cardNumberMasked,
                onValueChange = { v -> viewModel.update { it.copy(cardNumberMasked = v) } },
                label = { Text(numberLabel) },
                placeholder = { Text(numberHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (state.cardType == CardType.CREDIT) {
                CardMonthDayField(
                    value = state.statementDay,
                    label = statementDayLabel,
                    hint = dayOfMonthHint,
                    errorText = dayOutOfRange,
                    isError = state.isStatementDayInvalid,
                    onValueChange = { value ->
                        viewModel.update { it.copy(statementDay = value.filter(Char::isDigit)) }
                    },
                )
                CardMonthDayField(
                    value = state.repaymentDay,
                    label = repaymentDayLabel,
                    hint = dayOfMonthHint,
                    errorText = dayOutOfRange,
                    isError = state.isRepaymentDayInvalid,
                    onValueChange = { value ->
                        viewModel.update { it.copy(repaymentDay = value.filter(Char::isDigit)) }
                    },
                )
            }

            OutlinedTextField(
                value = state.requiredCount,
                onValueChange = { v ->
                    viewModel.update { it.copy(requiredCount = v.filter(Char::isDigit)) }
                },
                label = { Text(requiredLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
            )

            DateRow(
                label = validUntilLabel,
                unsetLabel = unsetText,
                clearLabel = clearText,
                selectLabel = selectText,
                millis = state.validUntilMillis,
                onClick = { dateDialogTarget = DateField.VALID_UNTIL },
                onClear = { viewModel.update { it.copy(validUntilMillis = null) } },
            )
            DateRow(
                label = nextDueLabel,
                unsetLabel = unsetText,
                clearLabel = clearText,
                selectLabel = selectText,
                millis = state.nextDueDateMillis,
                onClick = { dateDialogTarget = DateField.NEXT_DUE },
                onClear = { viewModel.update { it.copy(nextDueDateMillis = null) } },
                formatMillis = DateToken::formatAnnualDue,
                supportingText =
                    nextDueError.takeIf {
                        state.saveResult == CardEditSaveResult.ValidationError(CardEditValidation.NEXT_DUE_MUST_BE_FUTURE)
                    },
                isError = state.saveResult is CardEditSaveResult.ValidationError,
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                label = { Text(noteLabel) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                maxLines = 4,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                shape = MaterialTheme.shapes.large,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text(
                    saveBtnText,
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 日期选择器 ──
    val selectedDateField = dateDialogTarget
    if (selectedDateField != null) {
        val target = selectedDateField
        val initial =
            when (target) {
                DateField.VALID_UNTIL -> state.validUntilMillis ?: DateToken.fromLocalDate(LocalDate.now())
                DateField.NEXT_DUE -> state.nextDueDateMillis ?: DateToken.fromAnnualDate(LocalDate.now())
            }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { dateDialogTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    val ms = pickerState.selectedDateMillis
                    if (ms != null) {
                        viewModel.update {
                            when (target) {
                                DateField.VALID_UNTIL -> it.copy(validUntilMillis = ms)
                                DateField.NEXT_DUE ->
                                    it.copy(
                                        nextDueDateMillis =
                                            DateToken.fromAnnualDate(
                                                normalizeAnnualDueDate(DateToken.toLocalDate(ms)),
                                            ),
                                    )
                            }
                        }
                    }
                    dateDialogTarget = null
                }) { Text(confirmText) }
            },
            dismissButton = {
                TextButton(onClick = { dateDialogTarget = null }) { Text(cancelText) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showImageImportError) {
        AlertDialog(
            onDismissRequest = { showImageImportError = false },
            title = { Text(stringResource(R.string.edit_image_import_error_title)) },
            text = { Text(stringResource(R.string.edit_image_import_error_message)) },
            confirmButton = {
                TextButton(onClick = { showImageImportError = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    // ── 颜色选择器 BottomSheet ──
    if (showColorPicker) {
        ModalBottomSheet(
            onDismissRequest = { showColorPicker = false },
            sheetState = colorSheetState,
        ) {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
            ) {
                Text(
                    colorPickerTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                ModernColorPicker(
                    initialColor = Color(state.colorArgb),
                    onColorSelected = { c ->
                        viewModel.update { it.copy(colorArgb = c.toArgb()) }
                    },
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showColorPicker = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) { Text(doneText) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private enum class DateField { VALID_UNTIL, NEXT_DUE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardTypeSelector(
    current: CardType,
    unspecifiedLabel: String,
    debitLabel: String,
    creditLabel: String,
    onSelect: (CardType) -> Unit,
) {
    val options =
        listOf(
            CardType.UNSPECIFIED to unspecifiedLabel,
            CardType.DEBIT to debitLabel,
            CardType.CREDIT to creditLabel,
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = current == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CardMonthDayField(
    value: String,
    label: String,
    hint: String,
    errorText: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        supportingText =
            if (isError) {
                { Text(errorText) }
            } else {
                null
            },
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardEditLoadScaffold(
    title: String,
    backContentDescription: String,
    isLoading: Boolean,
    message: String?,
    retryLabel: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backContentDescription)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = message.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    onRetry?.let { retry ->
                        Button(onClick = retry) { Text(retryLabel) }
                    }
                }
            }
        }
    }
}

internal data class CardNetworkPickerPresentation(
    val visible: Boolean,
    val selectedKey: String?,
)

internal fun resolveCardNetworkPickerPresentation(
    sourceType: ImageSourceType,
    selectedKey: String?,
): CardNetworkPickerPresentation =
    CardNetworkPickerPresentation(
        visible = sourceType != ImageSourceType.USER,
        selectedKey = selectedKey,
    )

// ── 子组件 ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSourceSelector(
    current: ImageSourceType,
    providerLabel: String,
    userLabel: String,
    noneLabel: String,
    onSelect: (ImageSourceType) -> Unit,
) {
    val options =
        listOf(
            Triple(ImageSourceType.PROVIDER, providerLabel, Icons.Default.AccountBox),
            Triple(ImageSourceType.USER, userLabel, Icons.Default.Image),
            Triple(ImageSourceType.NONE, noneLabel, Icons.Default.LayersClear),
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (type, label, icon) ->
            SegmentedButton(
                selected = current == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrientationSelector(
    current: CardOrientation,
    landscapeLabel: String,
    portraitLabel: String,
    onSelect: (CardOrientation) -> Unit,
) {
    val options =
        listOf(
            CardOrientation.LANDSCAPE to landscapeLabel,
            CardOrientation.PORTRAIT to portraitLabel,
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (o, label) ->
            SegmentedButton(
                selected = current == o,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    Icon(
                        if (o == CardOrientation.LANDSCAPE) {
                            Icons.Default.Rotate90DegreesCcw
                        } else {
                            Icons.Default.Rotate90DegreesCw
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun FolderPicker(
    folders: List<com.shuaji.cards.data.local.CardFolderEntity>,
    currentId: Long?,
    unfiledLabel: String,
    onSelect: (Long?) -> Unit,
) {
    // FilterChip 按文字自然宽度排列，选项较多时可横向滚动。
    val allOptions =
        remember(folders) {
            listOf<Pair<Long?, com.shuaji.cards.data.local.CardFolderEntity?>>(
                null to null,
            ) + folders.map { it.id to it }
        }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
    ) {
        items(allOptions, key = { it.first ?: 0L }) { (id, folder) ->
            val selected = currentId == id
            val leadingIcon: @Composable () -> Unit = {
                val folderColorArgb = folder?.colorArgb
                if (folderColorArgb == null) {
                    Icon(
                        Icons.Default.LayersClear,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(FilterChipDefaults.IconSize)
                                .clip(CircleShape)
                                .background(
                                    androidx.compose.ui.graphics
                                        .Color(folderColorArgb),
                                ),
                    )
                }
            }
            FilterChip(
                selected = selected,
                onClick = { onSelect(id) },
                label = {
                    Text(
                        folder?.name ?: unfiledLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = leadingIcon,
            )
        }
    }
}

@Composable
private fun CardNetworkPicker(
    selectedKey: String?,
    onSelect: (CardNetworkProvider?) -> Unit,
) {
    val networks =
        buildList<CardNetworkProvider?> {
            add(null)
            addAll(CardNetworkProvider.entries)
        }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        items(networks) { network ->
            val selected = selectedKey == network?.key
            val displayName =
                network?.let { stringResource(it.displayNameRes) }
                    ?: stringResource(R.string.edit_network_none)
            Column(
                // 名字统一居中（American Express 2 行；其余 1 行居中显示在 2 行区域中部）
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .width(120.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ).border(
                            width = if (selected) 2.dp else 0.dp,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            shape = MaterialTheme.shapes.medium,
                        ).selectable(
                            selected = selected,
                            onClick = { onSelect(network) },
                            role = Role.RadioButton,
                        ).padding(8.dp),
            ) {
                // 白底 logo 框（确保 simple-icons 单色 logo 在白底上清晰可见）
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    if (network == null) {
                        Icon(
                            imageVector = Icons.Default.LayersClear,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Image(
                            painter = painterResource(network.logoRes),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 名字区域：固定容纳 2 行（≈ 40.dp），单行名字上下居中、American Express 上下两行铺满
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateRow(
    label: String,
    unsetLabel: String,
    clearLabel: String,
    selectLabel: String,
    millis: Long?,
    onClick: () -> Unit,
    onClear: () -> Unit,
    formatMillis: (Long) -> String = DateToken::format,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (millis != null) formatMillis(millis) else unsetLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row {
                if (millis != null) {
                    TextButton(onClick = onClear) { Text(clearLabel) }
                }
                TextButton(onClick = onClick) { Text(selectLabel) }
            }
        }
    }
}
