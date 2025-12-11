package com.vitol.inv3.data.remote

import com.vitol.inv3.auth.AuthManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber

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

class SupabaseRepository(
    private val client: SupabaseClient?,
    private val authManager: AuthManager
) {
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
        val userId = authManager.getCurrentUserId()
        if (userId == null) {
            Timber.w("No user ID available, cannot upsert company")
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
            
            // Check if company already exists before inserting
            // This handles the case where user wants to mark an existing company as "own"
            val existingCompany = findExistingCompany(normalizedCompany)
            if (existingCompany != null && !existingCompany.id.isNullOrBlank()) {
                val existingId = existingCompany.id
                // If marking as own company, find and delete duplicate records (same company_number/VAT but not marked as own)
                if (normalizedCompany.is_own_company == true) {
                    deleteDuplicateCompanies(normalizedCompany, existingId)
                }
                
                // Company exists, update it with new data (including is_own_company flag)
                val updated = client.from("companies").update(normalizedCompany.copy(id = existingId)) {
                    filter {
                        eq("id", existingId)
                    }
                    select()
                }.decodeSingle<CompanyRecord>()
                Timber.d("Company updated (marked as own): ${updated.company_name}")
                return@withContext updated
            }
            
            // Company doesn't exist, insert it
            // But first, if marking as own company, check for and delete any duplicates
            if (normalizedCompany.is_own_company == true) {
                // Find duplicates before inserting
                val duplicates = findDuplicateCompanies(normalizedCompany)
                // Delete duplicates that are NOT marked as own
                duplicates.filter { it.is_own_company != true && !it.id.isNullOrBlank() }
                    .forEach { duplicate ->
                        try {
                            client.from("companies").delete {
                                filter { eq("id", duplicate.id!!) }
                            }
                            Timber.d("Deleted duplicate company before insert: ${duplicate.company_name}")
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to delete duplicate before insert: ${duplicate.id}")
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
                    val existingCompany = findExistingCompany(normalizedCompany)
                    if (existingCompany != null && !existingCompany.id.isNullOrBlank()) {
                        val existingId = existingCompany.id
                        // If marking as own company, delete duplicates first
                        if (normalizedCompany.is_own_company == true) {
                            deleteDuplicateCompanies(normalizedCompany, existingId)
                        }
                        
                        val updated = client.from("companies").update(normalizedCompany.copy(id = existingId)) {
                            filter {
                                eq("id", existingId)
                            }
                            select()
                        }.decodeSingle<CompanyRecord>()
                        Timber.d("Company updated successfully: ${updated.company_name}")
                        return@withContext updated
                    }
                    
                    // Fallback: try to update by company_number or company_name
                    val updated = if (!normalizedCompany.company_number.isNullOrBlank()) {
                        client.from("companies").update(normalizedCompany) {
                            filter {
                                eq("company_number", normalizedCompany.company_number)
                            }
                            select()
                        }.decodeSingle<CompanyRecord>()
                    } else if (!normalizedCompany.company_name.isNullOrBlank()) {
                        client.from("companies").update(normalizedCompany) {
                            filter {
                                eq("company_name", normalizedCompany.company_name)
                            }
                            select()
                        }.decodeSingle<CompanyRecord>()
                    } else {
                        null
                    }
                    
                    if (updated != null) {
                        Timber.d("Company updated successfully: ${updated.company_name}")
                        return@withContext updated
                    } else {
                        Timber.e(e, "Cannot update company without company_number or company_name")
                        return@withContext null
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
    
    private suspend fun findExistingCompany(company: CompanyRecord): CompanyRecord? {
        if (client == null) return null
        
        return try {
            // Try to find by company_number first (most reliable - unique constraint)
            if (!company.company_number.isNullOrBlank()) {
                val result = client.from("companies")
                    .select {
                        filter {
                            eq("company_number", company.company_number)
                        }
                    }
                    .decodeSingleOrNull<CompanyRecord>()
                if (result != null) {
                    Timber.d("Found existing company by company_number: ${result.company_name}")
                    return result
                }
            }
            
            // Try to find by VAT number (normalize for query)
            val normalizedVat = normalizeVatNumber(company.vat_number)
            if (!normalizedVat.isNullOrBlank()) {
                val result = client.from("companies")
                    .select {
                        filter {
                            eq("vat_number", normalizedVat)
                        }
                    }
                    .decodeSingleOrNull<CompanyRecord>()
                if (result != null) {
                    Timber.d("Found existing company by vat_number: ${result.company_name}")
                    return result
                }
            }
            
            // Try to find by company name (less reliable, but useful if number/VAT not provided)
            if (!company.company_name.isNullOrBlank()) {
                val result = client.from("companies")
                    .select {
                        filter {
                            eq("company_name", company.company_name)
                        }
                    }
                    .decodeSingleOrNull<CompanyRecord>()
                if (result != null) {
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
     * Delete duplicate company records that have the same company_number or VAT number
     * but are NOT marked as own company. This prevents duplicates when marking a company as "own".
     */
    private suspend fun deleteDuplicateCompanies(company: CompanyRecord, keepId: String) = withContext(Dispatchers.IO) {
        if (client == null) return@withContext
        
        try {
            // Find all companies with the same company_number or VAT number
            val duplicates = mutableListOf<CompanyRecord>()
            
            if (!company.company_number.isNullOrBlank()) {
                val byNumber = client.from("companies")
                    .select {
                        filter {
                            eq("company_number", company.company_number)
                            neq("id", keepId) // Exclude the one we're keeping
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
                            neq("id", keepId) // Exclude the one we're keeping
                        }
                    }
                    .decodeList<CompanyRecord>()
                // Add only if not already in duplicates list
                duplicates.addAll(byVat.filter { dup -> 
                    !duplicates.any { it.id == dup.id }
                })
            }
            
            // Delete all duplicates that are NOT marked as own company
            duplicates.forEach { duplicate ->
                val duplicateId = duplicate.id
                if (duplicate.is_own_company != true && duplicateId != null) {
                    try {
                        client.from("companies").delete {
                            filter {
                                eq("id", duplicateId)
                            }
                        }
                        Timber.d("Deleted duplicate company: ${duplicate.company_name} (id: ${duplicate.id})")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to delete duplicate company: ${duplicate.id}")
                    }
                }
            }
            
            if (duplicates.isNotEmpty()) {
                Timber.d("Cleaned up ${duplicates.size} duplicate company records")
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
        val userId = authManager.getCurrentUserId()
        if (userId == null) {
            Timber.w("No user ID available, cannot insert invoice")
            return@withContext
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
        try {
            val invoices = client.from("invoices")
                .select()
                .decodeList<InvoiceRecord>()
            Timber.d("Fetched ${invoices.size} invoices from Supabase")
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
        try {
            // Format month as YYYY-MM for filtering
            val monthStr = String.format("%04d-%02d", year, month)
            val invoices = client.from("invoices")
                .select {
                    filter {
                        // Filter by date starting with YYYY-MM
                        like("date", "$monthStr%")
                    }
                }
                .decodeList<InvoiceRecord>()
            Timber.d("Fetched ${invoices.size} invoices for $monthStr from Supabase")
            invoices
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch invoices by month from Supabase")
            emptyList()
        }
    }

    suspend fun updateInvoice(invoice: InvoiceRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot update invoice")
            return@withContext
        }
        val userId = authManager.getCurrentUserId()
        if (userId == null) {
            Timber.w("No user ID available, cannot update invoice")
            return@withContext
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
        try {
            val companies = client.from("companies")
                .select {
                    filter {
                        eq("is_own_company", true)
                    }
                }
                .decodeList<CompanyRecord>()
            Timber.d("Fetched ${companies.size} own companies from Supabase")
            companies
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch own companies from Supabase")
            emptyList()
        }
    }

    suspend fun getCompanyById(companyId: String): CompanyRecord? = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot get company by id")
            return@withContext null
        }
        try {
            val company = client.from("companies")
                .select {
                    filter {
                        eq("id", companyId)
                    }
                }
                .decodeSingleOrNull<CompanyRecord>()
            Timber.d("Fetched company by id: ${company?.company_name}")
            company
        } catch (e: Exception) {
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
}

