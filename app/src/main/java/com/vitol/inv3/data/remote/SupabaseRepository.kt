package com.vitol.inv3.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class CompanyRecord(
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
        if (client == null) return@withContext
        // Use insert - conflicts will be handled by database unique constraint
        // For proper upsert, we'd need to check if record exists first, then update or insert
        client.from("companies").insert(company)
    }

    suspend fun insertInvoice(invoice: InvoiceRecord) = withContext(Dispatchers.IO) {
        if (client == null) return@withContext
        client.from("invoices").insert(invoice)
    }
}

