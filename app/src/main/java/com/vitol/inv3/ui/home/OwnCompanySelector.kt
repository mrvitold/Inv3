package com.vitol.inv3.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.vitol.inv3.data.local.setActiveOwnCompanyId
import com.vitol.inv3.data.remote.CompanyRecord
import kotlinx.coroutines.launch

@Composable
fun OwnCompanySelector(
    activeCompanyId: String?,
    activeCompanyName: String?,
    onCompanySelected: (String?) -> Unit,
    onShowSnackbar: (String) -> Unit,
    navController: NavHostController?,
    viewModel: OwnCompanyViewModel = hiltViewModel()
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddForm by remember { mutableStateOf(false) }
    var companyToEdit by remember { mutableStateOf<CompanyRecord?>(null) }
    var companyToRemove by remember { mutableStateOf<CompanyRecord?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownCompanies by viewModel.ownCompanies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadOwnCompanies()
    }
    
    // Auto-select company when app loads:
    // 1. If there's a stored activeCompanyId, ensure it's selected and name is loaded
    // 2. If no active company but only one company exists, auto-select it
    LaunchedEffect(ownCompanies, activeCompanyId, activeCompanyName, isLoading) {
        if (!isLoading && ownCompanies.isNotEmpty()) {
            // Case 1: There's a stored activeCompanyId but activeCompanyName is null
            // This means the company needs to be loaded/selected
            if (activeCompanyId != null && activeCompanyName == null) {
                val company = ownCompanies.find { it.id == activeCompanyId }
                if (company != null) {
                    // Company found in loaded list, trigger selection to ensure name is set
                    scope.launch {
                        context.setActiveOwnCompanyId(company.id)
                        onCompanySelected(company.id)
                    }
                } else {
                    // Stored company ID doesn't exist in loaded list, clear it
                    scope.launch {
                        context.setActiveOwnCompanyId(null)
                        onCompanySelected(null)
                    }
                }
            }
            // Case 2: No active company but only one company exists - auto-select it
            else if (activeCompanyId == null && ownCompanies.size == 1) {
                val singleCompany = ownCompanies.first()
                scope.launch {
                    context.setActiveOwnCompanyId(singleCompany.id)
                    onCompanySelected(singleCompany.id)
                }
            }
        }
    }
    
    // Your Company section
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Your company:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (activeCompanyName != null) {
                            Text(
                                text = activeCompanyName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = "Not selected",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    TextButton(onClick = {
                        if (activeCompanyName == null && ownCompanies.isEmpty() && !isLoading) {
                            showAddForm = true
                            companyToEdit = null
                        } else {
                            expanded = !expanded
                        }
                    }) {
                        Text(if (activeCompanyName == null) "Add" else "Change")
                    }
                }
                
                // Informational message when no company is selected
                if (activeCompanyName == null && ownCompanies.isEmpty() && !isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Add your company for better field detection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            // Dropdown Menu
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { 
                    expanded = false
                    showAddForm = false
                    companyToEdit = null
                },
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 600.dp)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Company list
                    if (ownCompanies.isEmpty()) {
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "No companies added yet.\nTap here or below to add one.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            onClick = { 
                                showAddForm = true
                                companyToEdit = null
                                expanded = false
                            }
                        )
                        HorizontalDivider()
                    } else {
                        ownCompanies.forEach { company ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = company.company_name ?: "Unknown",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        if (!company.company_number.isNullOrBlank() || !company.vat_number.isNullOrBlank()) {
                                            Text(
                                                text = listOfNotNull(
                                                    company.company_number,
                                                    company.vat_number
                                                ).joinToString(" • "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    scope.launch {
                                        context.setActiveOwnCompanyId(company.id)
                                        onCompanySelected(company.id)
                                        expanded = false
                                        onShowSnackbar("Company selected: ${company.company_name}")
                                    }
                                },
                                trailingIcon = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (company.id == activeCompanyId) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                companyToEdit = company
                                                showAddForm = false
                                                expanded = false
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                companyToRemove = company
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                    
                    DropdownMenuItem(
                        text = { Text("+ Add New Company") },
                        onClick = { 
                            showAddForm = true
                            companyToEdit = null
                            expanded = false
                        }
                    )
                }
            }
        }
    }
    
    // Add/Edit company dialog - scrollable, doesn't close on outside tap
    if (showAddForm || companyToEdit != null) {
        AddCompanyDialog(
            companyToEdit = companyToEdit,
            onSave = { companyId, wasEdit ->
                scope.launch {
                    if (companyId != null) {
                        context.setActiveOwnCompanyId(companyId)
                        onCompanySelected(companyId)
                    }
                    viewModel.loadOwnCompanies()
                    showAddForm = false
                    companyToEdit = null
                    onShowSnackbar(
                        if (wasEdit) "Company updated and selected" 
                        else "Company added and selected"
                    )
                }
            },
            onDismiss = {
                showAddForm = false
                companyToEdit = null
            },
            viewModel = viewModel
        )
    }
    
    // Confirmation dialog for removal
    companyToRemove?.let { company ->
        AlertDialog(
            onDismissRequest = { companyToRemove = null },
            title = { Text("Remove Company") },
            text = {
                Text("Are you sure you want to remove \"${company.company_name ?: "this company"}\" from your own companies?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.removeOwnCompany(company.id)
                            // If this was the active company, clear the selection
                            if (company.id == activeCompanyId) {
                                context.setActiveOwnCompanyId(null)
                                onCompanySelected(null)
                                onShowSnackbar("Company removed")
                            } else {
                                onShowSnackbar("Company removed")
                            }
                            viewModel.loadOwnCompanies()
                            companyToRemove = null
                            expanded = false
                        }
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { companyToRemove = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AddCompanyDialog(
    companyToEdit: CompanyRecord?,
    onSave: (String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    viewModel: OwnCompanyViewModel
) {
    var name by remember(companyToEdit?.id) { mutableStateOf(companyToEdit?.company_name ?: "") }
    var number by remember(companyToEdit?.id) { mutableStateOf(companyToEdit?.company_number ?: "") }
    var vat by remember(companyToEdit?.id) { mutableStateOf(companyToEdit?.vat_number ?: "") }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    
    LaunchedEffect(companyToEdit?.id) {
        name = companyToEdit?.company_name ?: ""
        number = companyToEdit?.company_number ?: ""
        vat = companyToEdit?.vat_number ?: ""
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (companyToEdit != null) "Edit Company" else "Add Your Company",
                    style = MaterialTheme.typography.titleLarge
                )
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Company name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Company number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                
                OutlinedTextField(
                    value = vat,
                    onValueChange = { vat = it },
                    label = { Text("VAT number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                val company = CompanyRecord(
                                    id = companyToEdit?.id,
                                    company_name = name,
                                    company_number = number,
                                    vat_number = vat,
                                    is_own_company = true
                                )
                                val savedCompany = viewModel.saveCompany(company)
                                onSave(savedCompany?.id, companyToEdit != null)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank()
                    ) {
                        Text(if (companyToEdit != null) "Update" else "Save")
                    }
                }
            }
        }
    }
}
