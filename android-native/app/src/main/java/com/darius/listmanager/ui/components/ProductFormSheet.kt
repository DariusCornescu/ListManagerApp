package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The one form for adding a product, from "De rezolvat" or from the catalog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFormSheet(
    title: String,
    distributors: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, distributor: String, aliases: String) -> Unit,
    subtitle: String? = null,
    initialName: String = "",
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully, so the keyboard doesn't push the save button out of reach.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ProductFormContent(title, distributors, onDismiss, onSave, subtitle, initialName)
    }
}

/** Saving needs a name and a distributor. Blank values never reach [onSave]. */
internal fun productFormCanSave(name: String, distributor: String): Boolean =
    name.isNotBlank() && distributor.isNotBlank()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductFormContent(
    title: String,
    distributors: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, distributor: String, aliases: String) -> Unit,
    subtitle: String? = null,
    initialName: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    var picked by remember { mutableStateOf("") }
    var addingNew by remember { mutableStateOf(false) }
    var newDistributor by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(16.dp)
    val scheme = MaterialTheme.colorScheme
    val distributor = if (addingNew) newDistributor else picked

    SheetBody(Modifier.verticalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nume produs *") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth(),
            )

            if (addingNew) {
                OutlinedTextField(
                    value = newDistributor,
                    onValueChange = { newDistributor = it },
                    label = { Text("Nume distribuitor nou *") },
                    singleLine = true,
                    shape = fieldShape,
                    trailingIcon = {
                        AppIconButton(Icons.Rounded.Close, "Alege un distribuitor existent", {
                            addingNew = false
                            newDistributor = ""
                        })
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = picked,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Distribuitor *") },
                        placeholder = { Text("Alege distribuitorul") },
                        shape = fieldShape,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        distributors.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    picked = option
                                    expanded = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Distribuitor nou") },
                            onClick = {
                                expanded = false
                                picked = ""
                                addingNew = true
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = aliases,
                onValueChange = { aliases = it },
                label = { Text("Sinonime (separate prin virgulă)") },
                placeholder = { Text("ex. lapte, lapte proaspăt") },
                supportingText = { Text("Opțional. Ajută recunoașterea vocală.") },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppPrimaryButton(
                    text = "Salvează",
                    onClick = { onSave(name.trim(), distributor.trim(), aliases.trim()) },
                    enabled = productFormCanSave(name, distributor),
                )
                AppSecondaryButton("Anulează", onDismiss)
            }
        }
    }
}
