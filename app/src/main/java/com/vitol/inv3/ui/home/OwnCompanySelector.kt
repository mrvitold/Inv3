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
import androidx.compose.ui.res.stringResource
import com.vitol.inv3.R
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
    addDialogTrigger: Int = 0,
    forceOpenAddDialog: Boolean = false,
    onAddDialogOpenHandled: (() -> Unit)? = null,
    navController: NavHostController?,
    viewModel: OwnCompanyViewModel = hiltViewModel()
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddForm by remember { mutableStateOf(false) }
    var companyToEdit by remember { mutableStateOf<CompanyRecord?>(null) }
    var companyToRemove by remember { mutableStateOf<CompanyRecord?>(null) }
    var showCompanyLimitDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thisCompanyFallback = stringResource(R.string.label_this_company)
    val scope = rememberCoroutineScope()
    val ownCompanies by viewModel.ownCompanies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadOwnCompanies()
    }

    LaunchedEffect(addDialogTrigger) {
        if (addDialogTrigger > 0) {
            showAddForm = true
            companyToEdit = null
            expanded = false
        }
    }

    LaunchedEffect(forceOpenAddDialog) {
        if (forceOpenAddDialog) {
            showAddForm = true
            companyToEdit = null
            expanded = false
            onAddDialogOpenHandled?.invoke()
        }
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
                            text = stringResource(R.string.own_company_label),
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
                                text = stringResource(R.string.common_not_selected),
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
                        Text(
                            if (activeCompanyName == null) stringResource(R.string.common_add)
                            else stringResource(R.string.common_change)
                        )
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
                            text = stringResource(R.string.own_company_hint),
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
                                    stringResource(R.string.own_company_no_companies),
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
                                            text = company.company_name
                                                ?: stringResource(R.string.common_unknown),
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
                                        onShowSnackbar(
                                            context.getString(
                                                R.string.own_company_selected,
                                                company.company_name ?: context.getString(R.string.common_unknown)
                                            )
                                        )
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
                                                contentDescription = stringResource(R.string.cd_selected),
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
                                                contentDescription = stringResource(R.string.cd_edit),
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
                                                contentDescription = stringResource(R.string.common_remove),
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
                        text = { Text(stringResource(R.string.own_company_add_new)) },
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
                        context.getString(
                            if (wasEdit) R.string.own_company_updated
                            else R.string.own_company_added
                        )
                    )
                }
            },
            onDismiss = {
                showAddForm = false
                companyToEdit = null
            },
            onLimitReached = { showCompanyLimitDialog = true },
            viewModel = viewModel
        )
    }
    
    // Company limit reached dialog
    if (showCompanyLimitDialog) {
        AlertDialog(
            onDismissRequest = { showCompanyLimitDialog = false },
            title = { Text(stringResource(R.string.own_company_limit_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.own_company_limit_body,
                        viewModel.maxOwnCompanies
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCompanyLimitDialog = false
                        navController?.navigate(com.vitol.inv3.Routes.Subscription)
                    }
                ) {
                    Text(stringResource(R.string.common_upgrade))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCompanyLimitDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Confirmation dialog for removal
    companyToRemove?.let { company ->
        AlertDialog(
            onDismissRequest = { companyToRemove = null },
            title = { Text(stringResource(R.string.own_company_remove_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.own_company_remove_body,
                        company.company_name ?: thisCompanyFallback
                    )
                )
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
                                onShowSnackbar(context.getString(R.string.own_company_removed))
                            } else {
                                onShowSnackbar(context.getString(R.string.own_company_removed))
                            }
                            viewModel.loadOwnCompanies()
                            companyToRemove = null
                            expanded = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_remove))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { companyToRemove = null }
                ) {
                    Text(stringResource(R.string.common_cancel))
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
    onLimitReached: () -> Unit,
    viewModel: OwnCompanyViewModel
) {
    var name by remember(companyToEdit?.id) { mutableStateOf(companyToEdit?.company_name ?: "") }
    var number by remember(companyToEdit?.id) { mutableStateOf(companyToEdit?.company_number ?: "") }
    var vat by remember(companyToEdit?.id) { mutableStateOf(companyToEdit?.vat_number ?: "") }
    var suppressSuggestionUi by remember(companyToEdit?.id) { mutableStateOf(false) }
    val suggestions by viewModel.nameSuggestions.collectAsState()
    val isSearchingSuggestions by viewModel.isSearchingSuggestions.collectAsState()
    val hasCompletedSuggestionSearch by viewModel.hasCompletedSuggestionSearch.collectAsState()
    val suggestionSearchTimedOut by viewModel.suggestionSearchTimedOut.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val dismissDialog = {
        viewModel.clearCompanyNameSuggestions()
        onDismiss()
    }
    val dialogTitle = if (companyToEdit != null) stringResource(R.string.dialog_edit_company)
    else stringResource(R.string.dialog_add_company)
    
    LaunchedEffect(companyToEdit?.id) {
        name = companyToEdit?.company_name ?: ""
        number = companyToEdit?.company_number ?: ""
        vat = companyToEdit?.vat_number ?: ""
        suppressSuggestionUi = false
        viewModel.clearCompanyNameSuggestions()
    }
    
    Dialog(
        onDismissRequest = dismissDialog,
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
                    text = dialogTitle,
                    style = MaterialTheme.typography.titleLarge
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.own_company_autofill_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        suppressSuggestionUi = false
                        viewModel.onCompanyNameInputChanged(it)
                    },
                    label = { Text(stringResource(R.string.label_company_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                val shouldShowSuggestions = name.trim().length >= 2 && !suppressSuggestionUi
                if (shouldShowSuggestions) {
                    when {
                        isSearchingSuggestions -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        suggestions.isNotEmpty() -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    suggestions.forEach { suggestion ->
                                        Text(
                                            text = buildString {
                                                append(suggestion.name)
                                                val trailing = listOfNotNull(
                                                    suggestion.jaKodas,
                                                    suggestion.vatNumber
                                                ).filter { it.isNotBlank() }
                                                if (trailing.isNotEmpty()) {
                                                    append("  •  ")
                                                    append(trailing.joinToString(" • "))
                                                }
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    name = suggestion.name
                                                    number = suggestion.jaKodas.orEmpty()
                                                    vat = suggestion.vatNumber.orEmpty()
                                                    suppressSuggestionUi = true
                                                    viewModel.clearCompanyNameSuggestions()
                                                    focusManager.clearFocus()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        )
                                        if (suggestion != suggestions.last()) {
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                        suggestionSearchTimedOut -> {
                            Text(
                                text = stringResource(R.string.own_company_suggestions_timeout),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        !hasCompletedSuggestionSearch -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        else -> {
                            Text(
                                text = stringResource(R.string.own_company_suggestions_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(stringResource(R.string.label_company_number)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                
                OutlinedTextField(
                    value = vat,
                    onValueChange = { vat = it },
                    label = { Text(stringResource(R.string.label_vat_number)) },
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
                        onClick = dismissDialog,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.common_cancel))
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
                                when (val result = viewModel.saveCompany(company, source = "home_selector")) {
                                    is OwnCompanyViewModel.SaveCompanyResult.Success -> {
                                        onSave(result.company.id, companyToEdit != null)
                                    }
                                    is OwnCompanyViewModel.SaveCompanyResult.LimitReached -> {
                                        dismissDialog()
                                        onLimitReached()
                                    }
                                    is OwnCompanyViewModel.SaveCompanyResult.Error -> {
                                        onSave(null, companyToEdit != null)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank()
                    ) {
                        Text(
                            if (companyToEdit != null) stringResource(R.string.common_update)
                            else stringResource(R.string.common_save)
                        )
                    }
                }
            }
        }
    }
}
