package com.vitol.inv3.data.remote

import com.vitol.inv3.auth.AuthManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.util.UUID

@Serializable
data class CompanyRecord(
    val id: String? = null,
    val company_number: String?,
    val company_name: String?,
    val vat_number: String?,
    val is_own_company: Boolean = false,
    val user_id: String? = null
)

@Serializable
data class InvoiceRecord(
    val id: String? = null,
    val invoice_id: String?,
    val date: String?,
    val company_name: String?,
    val amount_without_vat_eur: Double?,
    val vat_amount_eur: Double?,
    val vat_number: String?,
    val company_number: String?,
    val invoice_type: String? = "P", // P = Purchase/Received, S = Sales/Issued
    val vat_rate: Double? = null,
    val tax_code: String? = "PVM1",
    val user_id: String? = null
)

@Serializable
data class UserSubscriptionUpdate(
    val subscription_plan: String,
    val subscription_status: String,
    val subscription_start_date: String? = null,
    val subscription_end_date: String? = null,
    val purchase_token: String? = null
)

@Serializable
data class UserUsageUpdate(
    val pages_used: Int,
    val usage_reset_date: String? = null
)

@Serializable
data class UserUsageRecord(
    val pages_used: Int? = null,
    val usage_reset_date: String? = null
)

@Serializable
data class UserSubscriptionRecord(
    val subscription_plan: String? = null,
    val subscription_status: String? = null,
    val subscription_start_date: String? = null,
    val subscription_end_date: String? = null,
    val purchase_token: String? = null
)

class SupabaseRepository(
    private val client: SupabaseClient?,
    private val authManager: AuthManager
) {
    /**
     * Get current user ID from auth manager
     */
    private fun getCurrentUserId(): String? {
        return authManager.getCurrentUserId()
    }
    /**
     * Normalize VAT number: remove spaces, uppercase
     */
    private fun normalizeVatNumber(vatNumber: String?): String? {
        return vatNumber?.replace(" ", "")?.uppercase()?.takeIf { it.isNotBlank() }
    }
    
    suspend fun upsertCompany(company: CompanyRecord): CompanyRecord? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot upsert company")
            return@withContext null
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot upsert company")
            return@withContext null
        }
        // Normalize VAT number before saving (remove spaces, uppercase)
        val normalizedCompany = company.copy(
            vat_number = normalizeVatNumber(company.vat_number),
            user_id = userId
        )
        try {
            // If company has an ID, update it directly
            if (!normalizedCompany.id.isNullOrBlank()) {
                val updated = client.from("companies").update(normalizedCompany) {
                    filter {
                        eq("id", normalizedCompany.id)
                    }
                    select()
                }.decodeSingle<CompanyRecord>()
                Timber.d("Company updated successfully by ID: ${updated.company_name}")
                return@withContext updated
            }
            
            // Check if current user already has this company (by company_number/VAT)
            // This prevents duplicate companies for the same user
            val existingCompanyForUser = findExistingCompanyForCurrentUser(normalizedCompany)
            if (existingCompanyForUser != null && !existingCompanyForUser.id.isNullOrBlank()) {
                val existingId = existingCompanyForUser.id
                
                // Current user already has this company, update it
                // If marking as own company, find and delete duplicate records (same company_number/VAT but not marked as own)
                if (normalizedCompany.is_own_company == true) {
                    deleteDuplicateCompanies(normalizedCompany, existingId)
                }
                
                // Update the company (it belongs to current user, so RLS will allow it)
                val updateData = normalizedCompany.copy(
                    id = existingId,
                    user_id = userId  // Ensure user_id is set to current user
                )
                
                val updated = client.from("companies").update(updateData) {
                    filter {
                        eq("id", existingId)
                        eq("user_id", userId)  // Only update if it belongs to current user
                    }
                    select()
                }.decodeSingle<CompanyRecord>()
                Timber.d("Company updated for current user: ${updated.company_name}")
                return@withContext updated
            }
            
            // Current user doesn't have this company yet
            // Check if any company exists (for reference/verification), but we'll still create a new record for current user
            val anyExistingCompany = findExistingCompany(normalizedCompany)
            if (anyExistingCompany != null) {
                Timber.d("Company ${anyExistingCompany.company_name} exists for other user(s), creating new record for current user")
            }
            
            // Company doesn't exist for current user, insert it
            // But first, if marking as own company, check for and delete any duplicates belonging to current user
            if (normalizedCompany.is_own_company == true) {
                // Find duplicates belonging to current user before inserting
                val userId = getCurrentUserId()
                if (userId != null) {
                    val duplicates = mutableListOf<CompanyRecord>()
                    
                    if (!normalizedCompany.company_number.isNullOrBlank()) {
                        val byNumber = client.from("companies")
                            .select {
                                filter {
                                    eq("company_number", normalizedCompany.company_number)
                                    eq("user_id", userId) // Only find duplicates belonging to current user
                                }
                            }
                            .decodeList<CompanyRecord>()
                        duplicates.addAll(byNumber)
                    }
                    
                    if (!normalizedCompany.vat_number.isNullOrBlank()) {
                        val normalizedVat = normalizeVatNumber(normalizedCompany.vat_number)
                        if (!normalizedVat.isNullOrBlank()) {
                            val byVat = client.from("companies")
                                .select {
                                    filter {
                                        eq("vat_number", normalizedVat)
                                        eq("user_id", userId) // Only find duplicates belonging to current user
                                    }
                                }
                                .decodeList<CompanyRecord>()
                            duplicates.addAll(byVat.filter { dup -> 
                                !duplicates.any { it.id == dup.id }
                            })
                        }
                    }
                    
                    // Delete duplicates that are NOT marked as own and belong to current user
                    duplicates.filter { it.is_own_company != true && !it.id.isNullOrBlank() }
                        .forEach { duplicate ->
                            try {
                                client.from("companies").delete {
                                    filter { 
                                        eq("id", duplicate.id!!)
                                        eq("user_id", userId) // Only delete if belongs to current user
                                    }
                                }
                                Timber.d("Deleted duplicate company before insert: ${duplicate.company_name}")
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to delete duplicate before insert: ${duplicate.id}")
                            }
                        }
                }
            }
            
            val inserted = client.from("companies").insert(normalizedCompany) {
                select()
            }.decodeSingle<CompanyRecord>()
            Timber.d("Company inserted successfully: ${inserted.company_name}")
            return@withContext inserted
        } catch (e: Exception) {
            // If insert fails (likely due to unique constraint), try to update
            val isDuplicateError = e.message?.contains("duplicate", ignoreCase = true) == true ||
                    e.message?.contains("unique", ignoreCase = true) == true
            
            if (isDuplicateError) {
                try {
                    // Check if current user already has this company
                    val existingCompanyForUser = findExistingCompanyForCurrentUser(normalizedCompany)
                    if (existingCompanyForUser != null && !existingCompanyForUser.id.isNullOrBlank()) {
                        val existingId = existingCompanyForUser.id
                        
                        // Company exists and belongs to current user, update it
                        // If marking as own company, delete duplicates first
                        if (normalizedCompany.is_own_company == true) {
                            deleteDuplicateCompanies(normalizedCompany, existingId)
                        }
                        
                        // Update the company (it belongs to current user, so RLS will allow it)
                        val updateData = normalizedCompany.copy(
                            id = existingId,
                            user_id = userId  // Ensure user_id is set to current user
                        )
                        
                        val updated = client.from("companies").update(updateData) {
                            filter {
                                eq("id", existingId)
                                eq("user_id", userId)  // Only update if it belongs to current user
                            }
                            select()
                        }.decodeSingle<CompanyRecord>()
                        Timber.d("Company updated successfully: ${updated.company_name}")
                        return@withContext updated
                    }
                    
                    // Current user doesn't have this company, but duplicate error occurred
                    // This might be due to a race condition or other issue
                    // Try to create a new record anyway (with a slight delay to avoid race conditions)
                    Timber.d("Duplicate error but current user doesn't have company, retrying insert")
                    kotlinx.coroutines.delay(100) // Small delay to avoid race condition
                    val newCompany = normalizedCompany.copy(id = null) // Clear ID to force insert
                    val inserted = client.from("companies").insert(newCompany) {
                        select()
                    }.decodeSingle<CompanyRecord>()
                    Timber.d("Company inserted for current user after retry: ${inserted.company_name}")
                    return@withContext inserted
                    
                    // Fallback: try to update by company_number or company_name (only if belongs to current user)
                    // Note: RLS will enforce this, but we add explicit filter for clarity
                    val updated = if (!normalizedCompany.company_number.isNullOrBlank()) {
                        client.from("companies").update(normalizedCompany) {
                            filter {
                                eq("company_number", normalizedCompany.company_number)
                                eq("user_id", userId)  // Only update if belongs to current user
                            }
                            select()
                        }.decodeSingleOrNull<CompanyRecord>()
                    } else if (!normalizedCompany.company_name.isNullOrBlank()) {
                        client.from("companies").update(normalizedCompany) {
                            filter {
                                eq("company_name", normalizedCompany.company_name)
                                eq("user_id", userId)  // Only update if belongs to current user
                            }
                            select()
                        }.decodeSingleOrNull<CompanyRecord>()
                    } else {
                        null
                    }
                    
                    if (updated != null) {
                        Timber.d("Company updated successfully: ${updated.company_name}")
                        return@withContext updated
                    } else {
                        // Update failed - likely company doesn't belong to current user
                        // Create a new record instead
                        Timber.d("Fallback update failed (likely ownership issue), creating new record for current user")
                        val newCompany = normalizedCompany.copy(id = null) // Clear ID to force insert
                        try {
                            val inserted = client.from("companies").insert(newCompany) {
                                select()
                            }.decodeSingle<CompanyRecord>()
                            Timber.d("Company inserted for current user: ${inserted.company_name}")
                            return@withContext inserted
                        } catch (insertException: Exception) {
                            Timber.e(insertException, "Failed to insert company after update failure: ${company.company_name}")
                            return@withContext null
                        }
                    }
                } catch (updateException: Exception) {
                    Timber.e(updateException, "Failed to update company: ${company.company_name}")
                    return@withContext null
                }
            } else {
                Timber.e(e, "Failed to insert company: ${company.company_name}")
                return@withContext null
            }
        }
    }
    
    /**
     * Find if the current user already has a company with the same company_number/VAT.
     * This is used to prevent duplicate companies for the same user.
     */
    private suspend fun findExistingCompanyForCurrentUser(company: CompanyRecord): CompanyRecord? {
        if (client == null) return null
        val userId = getCurrentUserId()
        if (userId == null) return null
        
        return try {
            // Try to find by company_number first (most reliable)
            if (!company.company_number.isNullOrBlank()) {
                val results = client.from("companies")
                    .select {
                        filter {
                            eq("company_number", company.company_number)
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<CompanyRecord>()
                if (results.isNotEmpty()) {
                    val result = results.first()
                    Timber.d("Found existing company for current user by company_number: ${result.company_name}")
                    return result
                }
            }
            
            // Try to find by VAT number (normalize for query)
            val normalizedVat = normalizeVatNumber(company.vat_number)
            if (!normalizedVat.isNullOrBlank()) {
                val results = client.from("companies")
                    .select {
                        filter {
                            eq("vat_number", normalizedVat)
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<CompanyRecord>()
                if (results.isNotEmpty()) {
                    val result = results.first()
                    Timber.d("Found existing company for current user by vat_number: ${result.company_name}")
                    return result
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to find existing company for current user")
            null
        }
    }
    
    /**
     * Find any company with the same company_number/VAT (for shared verification database).
     * This searches across all users and is used for invoice scanning/verification.
     */
    private suspend fun findExistingCompany(company: CompanyRecord): CompanyRecord? {
        if (client == null) return null
        
        return try {
            // Try to find by company_number first (most reliable)
            if (!company.company_number.isNullOrBlank()) {
                val results = client.from("companies")
                    .select {
                        filter {
                            eq("company_number", company.company_number)
                        }
                    }
                    .decodeList<CompanyRecord>()
                if (results.isNotEmpty()) {
                    // Return the first one found (for verification purposes)
                    val result = results.first()
                    Timber.d("Found existing company by company_number: ${result.company_name}")
                    return result
                }
            }
            
            // Try to find by VAT number (normalize for query)
            val normalizedVat = normalizeVatNumber(company.vat_number)
            if (!normalizedVat.isNullOrBlank()) {
                val results = client.from("companies")
                    .select {
                        filter {
                            eq("vat_number", normalizedVat)
                        }
                    }
                    .decodeList<CompanyRecord>()
                if (results.isNotEmpty()) {
                    // Return the first one found (for verification purposes)
                    val result = results.first()
                    Timber.d("Found existing company by vat_number: ${result.company_name}")
                    return result
                }
            }
            
            // Try to find by company name (less reliable, but useful if number/VAT not provided)
            if (!company.company_name.isNullOrBlank()) {
                val results = client.from("companies")
                    .select {
                        filter {
                            eq("company_name", company.company_name)
                        }
                    }
                    .decodeList<CompanyRecord>()
                if (results.isNotEmpty()) {
                    // Return the first one found (for verification purposes)
                    val result = results.first()
                    Timber.d("Found existing company by company_name: ${result.company_name}")
                    return result
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to find existing company")
            null
        }
    }

    /**
     * Find duplicate company records that have the same company_number or VAT number
     */
    private suspend fun findDuplicateCompanies(company: CompanyRecord): List<CompanyRecord> = withContext(Dispatchers.IO) {
        if (client == null) return@withContext emptyList()
        
        val duplicates = mutableListOf<CompanyRecord>()
        
        try {
            if (!company.company_number.isNullOrBlank()) {
                val byNumber = client.from("companies")
                    .select {
                        filter {
                            eq("company_number", company.company_number)
                        }
                    }
                    .decodeList<CompanyRecord>()
                duplicates.addAll(byNumber)
            }
            
            if (!company.vat_number.isNullOrBlank()) {
                val byVat = client.from("companies")
                    .select {
                        filter {
                            eq("vat_number", company.vat_number)
                        }
                    }
                    .decodeList<CompanyRecord>()
                // Add only if not already in duplicates list
                duplicates.addAll(byVat.filter { dup -> 
                    !duplicates.any { it.id == dup.id }
                })
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to find duplicate companies")
        }
        
        return@withContext duplicates
    }

    /**
     * Delete duplicate company records that belong to the current user and have the same company_number or VAT number
     * but are NOT marked as own company. This prevents duplicates when marking a company as "own".
     * Note: Only deletes duplicates belonging to the current user - other users can have the same company.
     */
    private suspend fun deleteDuplicateCompanies(company: CompanyRecord, keepId: String) = withContext(Dispatchers.IO) {
        if (client == null) return@withContext
        val userId = getCurrentUserId()
        if (userId == null) return@withContext
        
        try {
            // Find all companies with the same company_number or VAT number that belong to the current user
            val duplicates = mutableListOf<CompanyRecord>()
            
            if (!company.company_number.isNullOrBlank()) {
                val byNumber = client.from("companies")
                    .select {
                        filter {
                            eq("company_number", company.company_number)
                            eq("user_id", userId) // Only find duplicates belonging to current user
                            neq("id", keepId) // Exclude the one we're keeping
                        }
                    }
                    .decodeList<CompanyRecord>()
                duplicates.addAll(byNumber)
            }
            
            if (!company.vat_number.isNullOrBlank()) {
                val normalizedVat = normalizeVatNumber(company.vat_number)
                if (!normalizedVat.isNullOrBlank()) {
                    val byVat = client.from("companies")
                        .select {
                            filter {
                                eq("vat_number", normalizedVat)
                                eq("user_id", userId) // Only find duplicates belonging to current user
                                neq("id", keepId) // Exclude the one we're keeping
                            }
                        }
                        .decodeList<CompanyRecord>()
                    // Add only if not already in duplicates list
                    duplicates.addAll(byVat.filter { dup -> 
                        !duplicates.any { it.id == dup.id }
                    })
                }
            }
            
            // Delete all duplicates that are NOT marked as own company
            duplicates.forEach { duplicate ->
                val duplicateId = duplicate.id
                if (duplicate.is_own_company != true && duplicateId != null) {
                    try {
                        client.from("companies").delete {
                            filter {
                                eq("id", duplicateId)
                                eq("user_id", userId) // Only delete if belongs to current user (RLS will enforce this)
                            }
                        }
                        Timber.d("Deleted duplicate company for current user: ${duplicate.company_name} (id: ${duplicate.id})")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to delete duplicate company: ${duplicate.id}")
                    }
                }
            }
            
            if (duplicates.isNotEmpty()) {
                Timber.d("Cleaned up ${duplicates.size} duplicate company records for current user")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete duplicate companies")
        }
    }

    suspend fun insertInvoice(invoice: InvoiceRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot insert invoice")
            return@withContext
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot insert invoice")
            throw IllegalStateException("User must be authenticated to insert invoice")
        }
        try {
            val invoiceWithUserId = invoice.copy(user_id = userId)
            client.from("invoices").insert(invoiceWithUserId)
            Timber.d("Invoice inserted successfully: ${invoice.invoice_id}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert invoice: ${invoice.invoice_id}")
            throw e
        }
    }

    suspend fun deleteCompany(company: CompanyRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot delete company")
            return@withContext
        }
        try {
            var deleted = false
            var lastError: Exception? = null
            
            // Try to delete by id first (most reliable - primary key)
            if (!company.id.isNullOrBlank()) {
                try {
                    // Verify company exists before deletion
                    val existing = client.from("companies").select {
                        filter {
                            eq("id", company.id)
                        }
                    }.decodeList<CompanyRecord>()
                    
                    if (existing.isNotEmpty()) {
                        client.from("companies").delete {
                            filter {
                                eq("id", company.id)
                            }
                        }
                        Timber.d("Company deleted successfully by id: ${company.id}")
                        deleted = true
                    } else {
                        Timber.w("Company with id ${company.id} not found, may have been already deleted")
                        deleted = true // Consider it deleted if it doesn't exist
                    }
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "Failed to delete by id: ${company.id}, error: ${e.message}")
                }
            }
            
            // If not deleted yet, try by company_number (unique constraint)
            if (!deleted && !company.company_number.isNullOrBlank()) {
                try {
                    client.from("companies").delete {
                        filter {
                            eq("company_number", company.company_number)
                        }
                    }
                    Timber.d("Company deleted successfully by number: ${company.company_number}")
                    deleted = true
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "Failed to delete by company_number: ${company.company_number}, error: ${e.message}")
                }
            }
            
            // If not deleted yet, try by company_name
            if (!deleted && !company.company_name.isNullOrBlank()) {
                try {
                    client.from("companies").delete {
                        filter {
                            eq("company_name", company.company_name)
                        }
                    }
                    Timber.d("Company deleted successfully by name: ${company.company_name}")
                    deleted = true
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "Failed to delete by company_name: ${company.company_name}, error: ${e.message}")
                }
            }
            
            // If still not deleted, try by vat_number
            if (!deleted && !company.vat_number.isNullOrBlank()) {
                try {
                    client.from("companies").delete {
                        filter {
                            eq("vat_number", company.vat_number)
                        }
                    }
                    Timber.d("Company deleted successfully by vat_number: ${company.vat_number}")
                    deleted = true
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "Failed to delete by vat_number: ${company.vat_number}, error: ${e.message}")
                }
            }
            
            if (!deleted) {
                if (lastError != null) {
                    Timber.e(lastError, "Failed to delete company after trying all methods: ${company.company_name ?: company.company_number ?: company.vat_number ?: company.id ?: "unknown"}")
                    throw lastError // Throw the error so the ViewModel can handle it
                } else {
                    val errorMsg = "Cannot delete company: all fields (id, company_number, company_name, vat_number) are null or blank"
                    Timber.w(errorMsg)
                    throw IllegalStateException(errorMsg)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete company: ${company.company_name ?: company.company_number ?: company.vat_number ?: company.id ?: "unknown"}")
            throw e // Re-throw to let the ViewModel handle it
        }
    }

    /**
     * Find company by VAT number or company number.
     * VAT number is considered more reliable (standardized format).
     * If both are provided, validates they belong to the same company.
     * 
     * @param companyNumber Company number (9 digits)
     * @param vatNumber VAT number (LT + 9 digits, normalized - no spaces)
     * @param excludeCompanyId Company ID to exclude from search
     * @return CompanyRecord if found, null otherwise
     */
    suspend fun findCompanyByNumberOrVat(
        companyNumber: String?, 
        vatNumber: String?,
        excludeCompanyId: String? = null
    ): CompanyRecord? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot find company")
            return@withContext null
        }
        try {
            // Normalize VAT number (remove spaces)
            val normalizedVat = vatNumber?.replace(" ", "")?.uppercase()
            
            // VAT number is more reliable - try it first
            // Try normalized version first (no spaces), then try original if provided (in case database has spaces)
            if (!normalizedVat.isNullOrBlank()) {
                // Try normalized version first
                var result = if (excludeCompanyId != null) {
                    client.from("companies")
                        .select {
                            filter {
                                eq("vat_number", normalizedVat)
                                neq("id", excludeCompanyId)
                            }
                        }
                        .decodeSingleOrNull<CompanyRecord>()
                } else {
                    client.from("companies")
                        .select {
                            filter {
                                eq("vat_number", normalizedVat)
                            }
                        }
                        .decodeSingleOrNull<CompanyRecord>()
                }
                
                // If normalized version not found and original VAT number is different, try original
                if (result == null && normalizedVat != null && vatNumber != null && vatNumber != normalizedVat) {
                    result = if (excludeCompanyId != null) {
                        client.from("companies")
                            .select {
                                filter {
                                    eq("vat_number", vatNumber)
                                    neq("id", excludeCompanyId)
                                }
                            }
                            .decodeSingleOrNull<CompanyRecord>()
                    } else {
                        client.from("companies")
                            .select {
                                filter {
                                    eq("vat_number", vatNumber)
                                }
                            }
                            .decodeSingleOrNull<CompanyRecord>()
                    }
                }
                
                if (result != null) {
                    // If company number was also provided, validate they match
                    if (!companyNumber.isNullOrBlank()) {
                        val normalizedCompanyNumber = companyNumber.trim()
                        val resultCompanyNumber = result.company_number?.trim()
                        if (resultCompanyNumber != null && normalizedCompanyNumber != resultCompanyNumber) {
                            Timber.w("VAT number $normalizedVat belongs to company with number $resultCompanyNumber, but extracted number is $normalizedCompanyNumber - using VAT number as authoritative")
                            // VAT number is more reliable, use the company number from database
                        }
                    }
                    Timber.d("Found company by vat_number: ${result.company_name}")
                    return@withContext result
                }
            }
            
            // Fallback: Try to find by company_number if VAT number not found
            if (!companyNumber.isNullOrBlank()) {
                val normalizedCompanyNumber = companyNumber.trim()
                val result = if (excludeCompanyId != null) {
                    client.from("companies")
                        .select {
                            filter {
                                eq("company_number", normalizedCompanyNumber)
                                neq("id", excludeCompanyId)
                            }
                        }
                        .decodeSingleOrNull<CompanyRecord>()
                } else {
                    client.from("companies")
                        .select {
                            filter {
                                eq("company_number", normalizedCompanyNumber)
                            }
                        }
                        .decodeSingleOrNull<CompanyRecord>()
                }
                if (result != null) {
                    // If VAT number was also provided, validate they match
                    if (!normalizedVat.isNullOrBlank()) {
                        val resultVatNumber = result.vat_number?.replace(" ", "")?.uppercase()
                        if (resultVatNumber != null && normalizedVat != resultVatNumber) {
                            Timber.w("Company number $normalizedCompanyNumber belongs to company with VAT $resultVatNumber, but extracted VAT is $normalizedVat - using company number as authoritative")
                            // Company number found, use the VAT number from database
                        }
                    }
                    Timber.d("Found company by company_number: ${result.company_name}")
                    return@withContext result
                }
            }
            
            Timber.d("No company found for company_number: $companyNumber, vat_number: $vatNumber")
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to find company by number or VAT")
            null
        }
    }

    suspend fun getAllInvoices(): List<InvoiceRecord> = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot fetch invoices")
            return@withContext emptyList()
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot fetch invoices")
            return@withContext emptyList()
        }
        try {
            val invoices = client.from("invoices")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<InvoiceRecord>()
            Timber.d("Fetched ${invoices.size} invoices from Supabase for user $userId")
            invoices
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch invoices from Supabase")
            emptyList()
        }
    }

    suspend fun getInvoicesByMonth(year: Int, month: Int): List<InvoiceRecord> = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot fetch invoices")
            return@withContext emptyList()
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot fetch invoices")
            return@withContext emptyList()
        }
        try {
            // Format month as YYYY-MM for filtering
            val monthStr = String.format("%04d-%02d", year, month)
            val invoices = client.from("invoices")
                .select {
                    filter {
                        // Filter by user_id AND date starting with YYYY-MM
                        eq("user_id", userId)
                        like("date", "$monthStr%")
                    }
                }
                .decodeList<InvoiceRecord>()
            Timber.d("Fetched ${invoices.size} invoices for $monthStr from Supabase for user $userId")
            invoices
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch invoices by month from Supabase")
            emptyList()
        }
    }

    /**
     * Find an invoice by VAT number or company number for partner company lookup.
     * Returns the first matching invoice to extract partner company parameters.
     * Filters by current user_id.
     */
    suspend fun findInvoiceByVatOrCompanyNumber(vatNumber: String?, companyNumber: String?): InvoiceRecord? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot find invoice")
            return@withContext null
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot find invoice")
            return@withContext null
        }
        try {
            // Normalize VAT number for comparison
            val normalizedVat = normalizeVatNumber(vatNumber)
            
            // Try to find by VAT number first (more reliable)
            if (!normalizedVat.isNullOrBlank()) {
                val results = client.from("invoices")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("vat_number", normalizedVat)
                        }
                    }
                    .decodeList<InvoiceRecord>()
                if (results.isNotEmpty()) {
                    val result = results.first()
                    Timber.d("Found invoice by VAT number: ${result.invoice_id} for partner company: ${result.company_name}")
                    return@withContext result
                }
            }
            
            // Fallback: Try to find by company number
            if (!companyNumber.isNullOrBlank()) {
                val normalizedCompanyNumber = companyNumber.trim()
                val results = client.from("invoices")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("company_number", normalizedCompanyNumber)
                        }
                    }
                    .decodeList<InvoiceRecord>()
                if (results.isNotEmpty()) {
                    val result = results.first()
                    Timber.d("Found invoice by company number: ${result.invoice_id} for partner company: ${result.company_name}")
                    return@withContext result
                }
            }
            
            Timber.d("No invoice found for VAT number: $vatNumber, company number: $companyNumber")
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to find invoice by VAT or company number")
            null
        }
    }

    suspend fun updateInvoice(invoice: InvoiceRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot update invoice")
            return@withContext
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot update invoice")
            throw IllegalStateException("User must be authenticated to update invoice")
        }
        try {
            if (invoice.id.isNullOrBlank()) {
                Timber.w("Cannot update invoice: id is null or blank")
                throw IllegalStateException("Invoice id is required for update")
            }
            val invoiceWithUserId = invoice.copy(user_id = userId)
            client.from("invoices").update(invoiceWithUserId) {
                filter {
                    eq("id", invoice.id)
                }
            }
            Timber.d("Invoice updated successfully: ${invoice.invoice_id}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update invoice: ${invoice.invoice_id}")
            throw e
        }
    }

    suspend fun deleteInvoice(invoice: InvoiceRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot delete invoice")
            return@withContext
        }
        try {
            if (invoice.id.isNullOrBlank()) {
                Timber.w("Cannot delete invoice: id is null or blank")
                throw IllegalStateException("Invoice id is required for deletion")
            }
            client.from("invoices").delete {
                filter {
                    eq("id", invoice.id)
                }
            }
            Timber.d("Invoice deleted successfully: ${invoice.invoice_id}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete invoice: ${invoice.invoice_id}")
            throw e
        }
    }

    suspend fun getAllOwnCompanies(): List<CompanyRecord> = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot get own companies")
            return@withContext emptyList()
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot get own companies")
            return@withContext emptyList()
        }
        try {
            val companies = client.from("companies")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("is_own_company", true)
                    }
                }
                .decodeList<CompanyRecord>()
            Timber.d("Fetched ${companies.size} own companies from Supabase for user $userId")
            companies
        } catch (e: Exception) {
            // Re-throw CancellationException to allow proper coroutine cancellation
            if (e is CancellationException) {
                throw e
            }
            Timber.e(e, "Failed to fetch own companies from Supabase")
            emptyList()
        }
    }

    suspend fun getCompanyById(companyId: String): CompanyRecord? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot get company by id")
            return@withContext null
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot get company by id")
            return@withContext null
        }
        try {
            // Only fetch company if it belongs to the current user
            val company = client.from("companies")
                .select {
                    filter {
                        eq("id", companyId)
                        eq("user_id", userId)
                    }
                }
                .decodeSingleOrNull<CompanyRecord>()
            Timber.d("Fetched company by id: ${company?.company_name} (user: $userId)")
            company
        } catch (e: Exception) {
            // Re-throw CancellationException to allow proper coroutine cancellation
            if (e is CancellationException) {
                throw e
            }
            Timber.e(e, "Failed to fetch company by id: $companyId")
            null
        }
    }

    suspend fun markAsOwnCompany(companyId: String) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot mark company as own")
            return@withContext
        }
        try {
            client.from("companies")
                .update(mapOf("is_own_company" to true)) {
                    filter {
                        eq("id", companyId)
                    }
                }
            Timber.d("Marked company $companyId as own company")
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark company as own: $companyId")
            throw e
        }
    }

    suspend fun unmarkAsOwnCompany(companyId: String) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot unmark company as own")
            return@withContext
        }
        try {
            client.from("companies")
                .update(mapOf("is_own_company" to false)) {
                    filter {
                        eq("id", companyId)
                    }
                }
            Timber.d("Unmarked company $companyId as own company")
        } catch (e: Exception) {
            Timber.e(e, "Failed to unmark company as own: $companyId")
            throw e
        }
    }

    /**
     * Get partner companies for the current user.
     * Partner companies are companies that belong to the user but are not marked as own companies.
     * These are typically companies discovered from invoices or explicitly added as partners.
     */
    suspend fun getPartnerCompanies(): List<CompanyRecord> = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot get partner companies")
            return@withContext emptyList()
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot get partner companies")
            return@withContext emptyList()
        }
        try {
            val companies = client.from("companies")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("is_own_company", false)
                    }
                }
                .decodeList<CompanyRecord>()
            Timber.d("Fetched ${companies.size} partner companies from Supabase for user $userId")
            companies
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch partner companies from Supabase")
            emptyList()
        }
    }
    
    /**
     * Update subscription status in Supabase.
     */
    suspend fun updateSubscriptionStatus(
        plan: String,
        isActive: Boolean,
        startDate: Long,
        purchaseToken: String? = null
    ) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot update subscription status")
            return@withContext
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot update subscription status")
            return@withContext
        }
        try {
            val endDate = if (isActive && plan != "free") {
                startDate + (30L * 24 * 60 * 60 * 1000) // 30 days from start
            } else {
                null
            }
            
            val updateData = UserSubscriptionUpdate(
                subscription_plan = plan,
                subscription_status = if (isActive) "active" else "expired",
                subscription_start_date = java.time.Instant.ofEpochMilli(startDate).toString(),
                subscription_end_date = endDate?.let { java.time.Instant.ofEpochMilli(it).toString() },
                purchase_token = purchaseToken
            )
            
            client.from("users").update(updateData) {
                filter {
                    eq("id", userId)
                }
            }
            Timber.d("Subscription status updated in Supabase: plan=$plan, active=$isActive")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update subscription status in Supabase")
        }
    }
    
    /**
     * Get subscription status from Supabase.
     */
    suspend fun getSubscriptionStatus(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot get subscription status")
            return@withContext null
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot get subscription status")
            return@withContext null
        }
        try {
            val result = client.from("users")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingle<Map<String, Any?>>()
            
            Timber.d("Fetched subscription status from Supabase")
            return@withContext result
        } catch (e: Exception) {
            Timber.e(e, "Failed to get subscription status from Supabase")
            null
        }
    }
    
    /**
     * Get usage count from Supabase.
     */
    suspend fun getUsageCount(): Pair<Int, Long?>? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot get usage count")
            return@withContext null
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot get usage count")
            return@withContext null
        }
        try {
            val result = client.from("users")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingle<UserUsageRecord>()
            
            val pagesUsed = result.pages_used ?: 0
            val resetDate = result.usage_reset_date?.let {
                try {
                    java.time.Instant.parse(it).toEpochMilli()
                } catch (e: Exception) {
                    null
                }
            }
            
            Timber.d("Fetched usage count from Supabase: pagesUsed=$pagesUsed, resetDate=$resetDate")
            return@withContext Pair(pagesUsed, resetDate)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get usage count from Supabase")
            null
        }
    }
    
    /**
     * Update usage count in Supabase.
     */
    suspend fun updateUsageCount(pagesUsed: Int, resetDate: Long? = null) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot update usage count")
            return@withContext
        }
        val userId = getCurrentUserId()
        if (userId == null) {
            Timber.w("User not authenticated, cannot update usage count")
            return@withContext
        }
        try {
            val updateData = UserUsageUpdate(
                pages_used = pagesUsed,
                usage_reset_date = resetDate?.let { java.time.Instant.ofEpochMilli(it).toString() }
            )
            
            client.from("users").update(updateData) {
                filter {
                    eq("id", userId)
                }
            }
            Timber.d("Usage count updated in Supabase: pagesUsed=$pagesUsed, resetDate=$resetDate")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update usage count in Supabase")
        }
    }
}

