package com.vitol.inv3.ui.exports

data class MonthlySummary(
    val month: String, // "2025-01" format for sorting
    val invoiceCount: Int,
    val totalAmount: Double,
    val totalVat: Double
)

data class CompanySummary(
    val companyName: String,
    val invoiceCount: Int,
    val totalAmount: Double,
    val totalVat: Double
)

