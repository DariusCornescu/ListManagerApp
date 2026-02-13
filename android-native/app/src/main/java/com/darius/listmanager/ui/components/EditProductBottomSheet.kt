package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProductBottomSheet(
    productName: String,
    distributors: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, distributor: String, aliases: String) -> Unit
) {
    var editedName by remember { mutableStateOf(productName) }
    var selectedDistributor by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var availableDistributors by remember { mutableStateOf(distributors) }
    var showAddDistributor by remember { mutableStateOf(false) }
    var newDistributorName by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Adaugă Produs în Catalog",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                label = { Text("Nume Produs *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Dropdown distribuitor
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedDistributor,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Distribuitor *") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableDistributors.forEach { distributor ->
                        DropdownMenuItem(
                            text = { Text(distributor) },
                            onClick = {
                                selectedDistributor = distributor
                                expanded = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("+ Adaugă Distribuitor Nou") },
                        onClick = {
                            expanded = false
                            showAddDistributor = true
                        }
                    )
                }
            }

            if (showAddDistributor) {
                OutlinedTextField(
                    value = newDistributorName,
                    onValueChange = { newDistributorName = it },
                    label = { Text("Nume Distribuitor Nou") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        Row {
                            IconButton(
                                onClick = {
                                    showAddDistributor = false
                                    newDistributorName = ""
                                }
                            ) {
                                Icon(Icons.Rounded.Close, "Anulează")
                            }
                            IconButton(
                                onClick = {
                                    if (newDistributorName.isNotBlank()) {
                                        availableDistributors = availableDistributors + newDistributorName.trim()
                                        selectedDistributor = newDistributorName.trim()
                                        newDistributorName = ""
                                        showAddDistributor = false
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.Check, "Adaugă")
                            }
                        }
                    }
                )
            }

            OutlinedTextField(
                value = aliases,
                onValueChange = { aliases = it },
                label = { Text("Sinonime (separate prin virgulă)") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ex. lapte, lapte proaspăt, lapte de vacă") },
                supportingText = { Text("Opțional: Adaugă nume alternative pentru recunoaștere vocală mai bună") },
                minLines = 2
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Anulează")
                }
                Button(
                    onClick = { onSave(editedName, selectedDistributor, aliases) },
                    modifier = Modifier.weight(1f),
                    enabled = editedName.isNotBlank() && selectedDistributor.isNotBlank()
                ) {
                    Text("Salvează")
                }
            }
        }
    }
}
