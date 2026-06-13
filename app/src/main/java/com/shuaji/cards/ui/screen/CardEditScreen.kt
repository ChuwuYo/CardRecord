package com.shuaji.cards.ui.screen

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.ModernColorPicker
import com.shuaji.cards.ui.component.horizontalEdgeFade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(
    cardId: Long?,
    onBack: () -> Unit,
) {
    val viewModel: CardEditViewModel = viewModel(factory = ViewModelFactories.Edit)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    LaunchedEffect(cardId) {
        if (cardId == null) viewModel.reset() else viewModel.load(cardId)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    var dateDialogTarget by remember { mutableStateOf<DateField?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    val colorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri != null) {
                viewModel.update {
                    it.copy(imageUri = uri.toString(), imageSourceType = ImageSourceType.USER)
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (cardId == null) "添加卡片" else "编辑卡片",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }, enabled = state.canSave) {
                        Text("保存")
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
            // ── 卡面来源 ──
            Text(
                "卡面",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImageSourceSelector(
                current = state.imageSourceType,
                onSelect = { type ->
                    viewModel.update { s ->
                        s.copy(
                            imageSourceType = type,
                            imageUri = if (type == ImageSourceType.NONE) null else s.imageUri,
                            imageProviderKey =
                                if (type == ImageSourceType.PROVIDER) {
                                    (s.imageProviderKey ?: CardNetworkProvider.VISA.key)
                                } else {
                                    null
                                },
                        )
                    }
                },
            )

            if (state.imageSourceType == ImageSourceType.PROVIDER) {
                Text(
                    "选择卡组织",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardNetworkPicker(
                    selectedKey = state.imageProviderKey,
                    onSelect = { network ->
                        viewModel.update {
                            it.copy(
                                imageProviderKey = network.key,
                                colorArgb = network.brandColor,
                                // 横/竖朝向不再按卡组织自动预判，完全由用户决定
                            )
                        }
                    },
                )
            }

            if (state.imageSourceType == ImageSourceType.USER) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clickable { imagePicker.launch("image/*") },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.imageUri != null) {
                            coil3.compose.AsyncImage(
                                model = state.imageUri,
                                contentDescription = "卡面",
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.medium),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { viewModel.update { it.copy(imageUri = null) } },
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                            ) {
                                Icon(
                                    Icons.Default.LayersClear,
                                    contentDescription = "清除图片",
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
                                    "点击选择卡面图片",
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
                "卡面朝向",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OrientationSelector(
                current = state.cardOrientation,
                onSelect = { o -> viewModel.update { it.copy(cardOrientation = o) } },
            )

            // ── 文件夹 ──
            if (folders.isNotEmpty()) {
                Text(
                    "文件夹",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FolderPicker(
                    folders = folders,
                    currentId = state.folderId,
                    onSelect = { id -> viewModel.update { it.copy(folderId = id) } },
                )
            }

            // ── 主题色（自定义调色板） ──
            Text(
                "主题色",
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
                label = { Text("卡片名称 *") },
                placeholder = { Text("例如：商旅白金卡") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.bank,
                onValueChange = { v -> viewModel.update { it.copy(bank = v) } },
                label = { Text("发卡行（手动输入）") },
                placeholder = { Text("例如：招商银行 / HSBC / Chase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.cardNumberMasked,
                onValueChange = { v -> viewModel.update { it.copy(cardNumberMasked = v) } },
                label = { Text("卡号（建议脱敏后四位）") },
                placeholder = { Text("****  ****  ****  1234") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.requiredCount,
                    onValueChange = { v ->
                        viewModel.update { it.copy(requiredCount = v.filter(Char::isDigit)) }
                    },
                    label = { Text("所需笔数 *") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                )
                OutlinedTextField(
                    value = state.currentCount,
                    onValueChange = { v ->
                        viewModel.update { it.copy(currentCount = v.filter(Char::isDigit)) }
                    },
                    label = { Text("当前笔数") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                )
            }

            DateRow(
                label = "卡片有效期",
                millis = state.validUntilMillis,
                onClick = { dateDialogTarget = DateField.VALID_UNTIL },
                onClear = { viewModel.update { it.copy(validUntilMillis = null) } },
            )
            DateRow(
                label = "下次年费结算日",
                millis = state.nextDueDateMillis,
                onClick = { dateDialogTarget = DateField.NEXT_DUE },
                onClear = { viewModel.update { it.copy(nextDueDateMillis = null) } },
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                label = { Text("备注") },
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
                    if (cardId == null) "保存并添加到列表" else "保存修改",
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 日期选择器 ──
    if (dateDialogTarget != null) {
        val target = dateDialogTarget!!
        val initial =
            when (target) {
                DateField.VALID_UNTIL -> state.validUntilMillis
                DateField.NEXT_DUE -> state.nextDueDateMillis
            } ?: System.currentTimeMillis()
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
                                DateField.NEXT_DUE -> it.copy(nextDueDateMillis = ms)
                            }
                        }
                    }
                    dateDialogTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dateDialogTarget = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    // ── 颜色选择器 BottomSheet ──
    if (showColorPicker) {
        ModalBottomSheet(
            onDismissRequest = { showColorPicker = false },
            sheetState = colorSheetState,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "选择主题色",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                ModernColorPicker(
                    initialColor = Color(state.colorArgb),
                    onColorSelected = { c ->
                        viewModel.update { it.copy(colorArgb = c.toComposeArgb()) }
                    },
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showColorPicker = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) { Text("完成") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private enum class DateField { VALID_UNTIL, NEXT_DUE }

// ── 子组件 ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSourceSelector(
    current: ImageSourceType,
    onSelect: (ImageSourceType) -> Unit,
) {
    val options =
        listOf(
            Triple(ImageSourceType.PROVIDER, "预设卡组织", Icons.Default.AccountBox),
            Triple(ImageSourceType.USER, "自定义上传", Icons.Default.Image),
            Triple(ImageSourceType.NONE, "纯色卡", Icons.Default.LayersClear),
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
    onSelect: (CardOrientation) -> Unit,
) {
    val options =
        listOf(
            CardOrientation.LANDSCAPE to "横版",
            CardOrientation.PORTRAIT to "竖版",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPicker(
    folders: List<com.shuaji.cards.data.local.CardFolderEntity>,
    currentId: Long?,
    onSelect: (Long?) -> Unit,
) {
    val allOptions =
        remember(folders) {
            listOf<Pair<Long?, com.shuaji.cards.data.local.CardFolderEntity?>>(
                null to null,
            ) + folders.map { it.id to it }
        }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        allOptions.forEachIndexed { index, (id, folder) ->
            val selected = currentId == id
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(id) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = allOptions.size),
                icon = {
                    if (id == null) {
                        Icon(Icons.Default.LayersClear, contentDescription = null, modifier = Modifier.size(16.dp))
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(
                                        androidx.compose.ui.graphics
                                            .Color(folder!!.colorArgb),
                                    ),
                        )
                    }
                },
            ) {
                Text(
                    folder?.name ?: "未分类",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CardNetworkPicker(
    selectedKey: String?,
    onSelect: (CardNetworkProvider) -> Unit,
) {
    LazyRow(
        modifier = Modifier.horizontalEdgeFade(fadeWidth = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(CardNetworkProvider.entries) { network ->
            val selected = selectedKey == network.key
            Column(
                // 名字统一居中（American Express 2 行；其余 1 行居中显示在 2 行区域中部）
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .width(110.dp)
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
                        ).clickable { onSelect(network) }
                        .padding(8.dp),
            ) {
                // 白底 logo 框（确保 simple-icons 单色 logo 在白底上清晰可见）
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(network.logoRes),
                        contentDescription = network.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.padding(6.dp),
                    )
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
                        text = network.displayName,
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
    millis: Long?,
    onClick: () -> Unit,
    onClear: () -> Unit,
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
                    if (millis != null) formatDate(millis) else "未设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row {
                if (millis != null) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
                TextButton(onClick = onClick) { Text("选择") }
            }
        }
    }
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}

private fun Color.toComposeArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
