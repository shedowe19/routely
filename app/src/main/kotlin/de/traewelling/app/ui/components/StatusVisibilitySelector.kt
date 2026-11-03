package de.traewelling.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import de.traewelling.app.data.model.StatusVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusVisibilitySelector(
    selectedValue: Int?,
    onSelected: (Int) -> Unit,
    enabled: Boolean = true,
    locked: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = StatusVisibility.fromApiValue(selectedValue)
    val title = selected?.title ?: "Unbekannte Sichtbarkeit"
    val description = when {
        locked -> "Die Sichtbarkeit wurde durch die Moderation gesperrt."
        selected != null -> selected.description
        else -> "Bleibt unverändert, solange du keine neue Sichtbarkeit auswählst."
    }
    val canSelect = enabled && !locked

    ExposedDropdownMenuBox(
        expanded = expanded && canSelect,
        onExpandedChange = { if (canSelect) expanded = it }
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = {},
            readOnly = true,
            enabled = canSelect,
            label = { Text("Sichtbarkeit") },
            supportingText = { Text(description) },
            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && canSelect) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = canSelect)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded && canSelect,
            onDismissRequest = { expanded = false }
        ) {
            StatusVisibility.entries.forEach { visibility ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(visibility.title)
                            Text(visibility.description, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingIcon = {
                        if (selected == visibility) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = { if (canSelect) onSelected(visibility.apiValue); expanded = false }
                )
            }
        }
    }
}
