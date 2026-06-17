package com.shuaji.cards.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.ModernColorPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardFolderScreen(onBack: () -> Unit) {
    val viewModel: CardFolderViewModel = viewModel(factory = ViewModelFactories.Folders)
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    LaunchedEffect(folders) {
        viewModel.refreshCounts()
    }

    var dialogTarget by remember { mutableStateOf<DialogTarget?>(null) }
    var folderToDelete by remember { mutableStateOf<CardFolderEntity?>(null) }

    val titleText = stringResource(R.string.folder_title)
    val backCd = stringResource(R.string.common_back)
    val createText = stringResource(R.string.common_create)
    val editCd = stringResource(R.string.common_edit)
    val deleteCd = stringResource(R.string.common_delete)
    val cancelText = stringResource(R.string.common_cancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backCd)
                    }
                },
                actions = {
                    TextButton(onClick = { dialogTarget = DialogTarget.Create }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(createText)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            EmptyFolderState(onCreate = { dialogTarget = DialogTarget.Create })
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(folders, key = { it.id }) { folder ->
                    FolderRow(
                        folder = folder,
                        cardCount = counts[folder.id] ?: 0,
                        editCd = editCd,
                        deleteCd = deleteCd,
                        onClick = { dialogTarget = DialogTarget.Edit(folder) },
                        onDelete = { folderToDelete = folder },
                    )
                }
            }
        }
    }

    when (val target = dialogTarget) {
        is DialogTarget.Create -> {
            FolderEditDialog(
                initial = null,
                onDismiss = { dialogTarget = null },
                onConfirm = { name, color ->
                    viewModel.create(name, color)
                    dialogTarget = null
                },
            )
        }
        is DialogTarget.Edit -> {
            FolderEditDialog(
                initial = target.folder,
                onDismiss = { dialogTarget = null },
                onConfirm = { name, color ->
                    viewModel.update(target.folder, name, color)
                    dialogTarget = null
                },
            )
        }
        null -> Unit
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.folder_delete_title, folder.name)) },
            text = { Text(stringResource(R.string.folder_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(folder)
                    folderToDelete = null
                }) { Text(deleteCd) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) { Text(cancelText) }
            },
        )
    }
}

private sealed interface DialogTarget {
    data object Create : DialogTarget

    data class Edit(
        val folder: CardFolderEntity,
    ) : DialogTarget
}

@Composable
private fun FolderRow(
    folder: CardFolderEntity,
    cardCount: Int,
    editCd: String,
    deleteCd: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(folder.colorArgb)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.folder_count_label, cardCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Edit, contentDescription = editCd, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = deleteCd, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyFolderState(onCreate: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.folder_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.folder_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.folder_create))
        }
    }
}

@Composable
private fun FolderEditDialog(
    initial: CardFolderEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.colorArgb ?: 0xFF42A5F5.toInt()) }

    val titleText =
        stringResource(
            if (initial == null) R.string.folder_dialog_create_title else R.string.folder_dialog_edit_title,
        )
    val nameLabel = stringResource(R.string.folder_dialog_field_name)
    val colorLabel = stringResource(R.string.folder_dialog_field_color)
    val saveText = stringResource(R.string.common_save)
    val cancelText = stringResource(R.string.common_cancel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(nameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    colorLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 复用卡片编辑页的 ModernColorPicker：和卡片主题色取色器视觉一致
                ModernColorPicker(
                    initialColor = Color(color),
                    onColorSelected = { c -> color = c.toArgb() },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, color) },
                enabled = name.isNotBlank(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
            ) { Text(saveText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelText) }
        },
    )
}
