package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.RouteEntity
import com.example.data.local.RouteFolderEntity
import com.example.ui.theme.ExpressiveTheme

val DefaultFolderPaletteColors: List<Long> = listOf(
    0xFF1B6B52L,
    0xFF2563EBL,
    0xFFEA580CL,
    0xFF7C3AEDL,
    0xFFFF4D8DL,
    0xFF0D9488L
)

@Composable
fun FolderEditorDialog(
    initialFolder: RouteFolderEntity? = null,
    onConfirm: (name: String, colorArgb: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val isEditing = initialFolder != null
    var folderName by remember(initialFolder) { mutableStateOf(initialFolder?.name ?: "") }
    var selectedColor by remember(initialFolder) {
        mutableLongStateOf(initialFolder?.colorArgb ?: DefaultFolderPaletteColors.first())
    }
    var showColorPicker by remember { mutableStateOf(false) }

    val colorChoices = remember(selectedColor) {
        (DefaultFolderPaletteColors + selectedColor).distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Text(
                text = if (isEditing) "Edit Folder" else "New Route Folder",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name (e.g. Weekend Hikes)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder_name_input")
                )

                Text(
                    text = "Folder Accent Color",
                    style = MaterialTheme.typography.labelLarge
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorChoices.forEach { argb ->
                        val selected = selectedColor == argb
                        Surface(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable { selectedColor = argb },
                            shape = CircleShape,
                            color = Color(argb),
                            border = if (selected) {
                                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                            } else {
                                BorderStroke(1.dp, Color.White)
                            }
                        ) {}
                    }
                    Surface(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable { showColorPicker = true }
                            .testTag("folder_custom_color_button"),
                        shape = CircleShape,
                        color = ExpressiveTheme.colors.nestedElementSurface,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Pick custom folder color",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = if (isEditing) "Save" else "Create",
                enabled = folderName.isNotBlank(),
                onClick = { onConfirm(folderName.trim(), selectedColor) },
                modifier = Modifier.testTag("confirm_folder_button")
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showColorPicker) {
        CustomColorPickerDialog(
            initialColorArgb = selectedColor,
            title = "Folder Color",
            subtitle = "Choose an accent color for this route folder",
            onConfirm = { picked ->
                selectedColor = picked
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}

@Composable
fun DeleteFolderConfirmDialog(
    folder: RouteFolderEntity,
    routeCount: Int,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Text(
                text = "Delete \"${folder.name}\"?",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = if (routeCount > 0) {
                    "This folder contains $routeCount saved route${if (routeCount == 1) "" else "s"}. Deleting the folder keeps all of its saved routes in \"All\"."
                } else {
                    "This empty folder will be removed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Delete Folder",
                onClick = onConfirmDelete,
                containerColorOverride = MaterialTheme.colorScheme.errorContainer,
                contentColorOverride = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("confirm_delete_folder_button")
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun MoveRouteToFolderDialog(
    route: RouteEntity,
    folders: List<RouteFolderEntity>,
    onSelectFolder: (Long?) -> Unit,
    onCreateAndSelectFolder: (name: String, colorArgb: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateFolder by remember { mutableStateOf(false) }
    val totalOptions = folders.size + 1

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Move Route to Folder",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "no_folder") {
                        FolderAssignmentRow(
                            label = "No Folder (All only)",
                            color = null,
                            selected = route.folderId == null,
                            shape = groupedCardShape(0, totalOptions),
                            onClick = { onSelectFolder(null) },
                            modifier = Modifier.testTag("move_target_no_folder")
                        )
                    }
                    itemsIndexed(folders, key = { _, f -> f.id }) { index, folder ->
                        FolderAssignmentRow(
                            label = folder.name,
                            color = Color(folder.colorArgb),
                            selected = route.folderId == folder.id,
                            shape = groupedCardShape(index + 1, totalOptions),
                            onClick = { onSelectFolder(folder.id) },
                            modifier = Modifier.testTag("move_target_folder_${folder.id}")
                        )
                    }
                }

                ExpressivePillButton(
                    text = "New Folder",
                    icon = Icons.Filled.CreateNewFolder,
                    subtle = true,
                    onClick = { showCreateFolder = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("move_dialog_new_folder_button")
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )

    if (showCreateFolder) {
        FolderEditorDialog(
            onConfirm = { name, colorArgb ->
                showCreateFolder = false
                onCreateAndSelectFolder(name, colorArgb)
            },
            onDismiss = { showCreateFolder = false }
        )
    }
}

@Composable
private fun FolderAssignmentRow(
    label: String,
    color: Color?,
    selected: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            ExpressiveTheme.colors.nestedElementSurface
        },
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (color != null) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Current folder",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun FolderBadgeChip(
    folder: RouteFolderEntity?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val label = folder?.name ?: "No Folder"
    val accent = folder?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(percent = 50)).clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(percent = 50),
        color = ExpressiveTheme.colors.nestedElementSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (folder != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
