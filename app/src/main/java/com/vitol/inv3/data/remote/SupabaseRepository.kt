package com.vitol.inv3.data.remote

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
    val vat_number: String?
)

@Serializable
data class InvoiceRecord(
    val invoice_id: String?,
    val date: String?,
    val company_name: String?,
    val amount_without_vat_eur: Double?,
    val vat_amount_eur: Double?,
    val vat_number: String?,
    val company_number: String?
)

class SupabaseRepository(private val client: SupabaseClient?) {
    suspend fun upsertCompany(company: CompanyRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot upsert company")
            return@withContext
        }
        try {
            // Try to insert first
            client.from("companies").insert(company)
            Timber.d("Company inserted successfully: ${company.company_name}")
        } catch (e: Exception) {
            // If insert fails (likely due to unique constraint), try to update
            val isDuplicateError = e.message?.contains("duplicate", ignoreCase = true) == true ||
                    e.message?.contains("unique", ignoreCase = true) == true
            
            if (isDuplicateError) {
                try {
                    if (!company.company_number.isNullOrBlank()) {
                        client.from("companies").update(company) {
                            filter {
                                eq("company_number", company.company_number)
                            }
                        }
                        Timber.d("Company updated successfully: ${company.company_name}")
                    } else {
                        // If company_number is blank, try to update by company_name if available
                        if (!company.company_name.isNullOrBlank()) {
                            try {
                                client.from("companies").update(company) {
                                    filter {
                                        eq("company_name", company.company_name)
                                    }
                                }
                                Timber.d("Company updated successfully by name: ${company.company_name}")
                            } catch (nameUpdateException: Exception) {
                                Timber.e(nameUpdateException, "Failed to update company by name: ${company.company_name}")
                                // Don't throw - just log the error
                            }
                        } else {
                            Timber.e(e, "Cannot update company without company_number or company_name")
                            // Don't throw - just log the error
                        }
                    }
                } catch (updateException: Exception) {
                    Timber.e(updateException, "Failed to update company: ${company.company_name}")
                    // Don't throw - just log the error to prevent app crash
                }
            } else {
                Timber.e(e, "Failed to insert company: ${company.company_name}")
                // Don't throw - just log the error to prevent app crash
            }
        }
    }

    suspend fun insertInvoice(invoice: InvoiceRecord) = withContext(Dispatchers.IO) {
        if (client == null) {
            Timber.w("Supabase client is null, cannot insert invoice")
            return@withContext
        }
        try {
            client.from("invoices").insert(invoice)
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
                    client.from("companies").delete {
                        filter {
                            eq("id", company.id)
                        }
                    }
                    Timber.d("Company deleted successfully by id: ${company.id}")
                    deleted = true
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "Failed to delete by id: ${company.id}")
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
                    Timber.w(e, "Failed to delete by company_number: ${company.company_number}")
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
                    Timber.w(e, "Failed to delete by company_name: ${company.company_name}")
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
                    Timber.w(e, "Failed to delete by vat_number: ${company.vat_number}")
                }
            }
            
            if (!deleted) {
                if (lastError != null) {
                    Timber.e(lastError, "Failed to delete company after trying all methods: ${company.company_name ?: company.company_number ?: company.vat_number ?: company.id ?: "unknown"}")
                } else {
                    Timber.w("Cannot delete company: all fields (id, company_number, company_name, vat_number) are null or blank")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete company: ${company.company_name ?: company.company_number ?: company.vat_number ?: company.id ?: "unknown"}")
            // Don't throw - just log the error to prevent app crash
        }
    }
}

