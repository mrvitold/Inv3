package com.vitol.inv3.ocr

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import android.util.Base64

/**
 * Service for processing invoices using Azure AI Document Intelligence REST API.
 * 
 * Setup required:
 * 1. Create an Azure Document Intelligence resource
 * 2. Get your endpoint and API key from Azure Portal
 * 3. Update endpoint and apiKey below
 */
class AzureDocumentIntelligenceService(private val context: Context) {
    
    // Azure Document Intelligence configuration
    // Credentials are loaded from BuildConfig (set in gradle.properties)
    // For security, credentials are stored in gradle.properties (not committed to git)
    private val endpoint = com.vitol.inv3.BuildConfig.AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT
    private val apiKey = com.vitol.inv3.BuildConfig.AZURE_DOCUMENT_INTELLIGENCE_API_KEY
    private val apiVersion = "2023-07-31"  // API version
    
    private val analyzeUrl = "${endpoint}formrecognizer/documentModels/prebuilt-invoice:analyze?api-version=$apiVersion"
    
    private val gson = Gson()
    private val httpClient = OkHttpClient()
    
    /**
     * Process an invoice image using Azure Document Intelligence REST API.
     * 
     * @param imageUri URI of the invoice image
     * @return ParsedInvoice with extracted fields, or null if processing failed
     */
    suspend fun processInvoice(imageUri: Uri, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null): ParsedInvoice? = withContext(Dispatchers.IO) {
        try {
            // Validate configuration
            if (apiKey.isBlank() || endpoint.isBlank()) {
                Timber.e("Azure Document Intelligence not configured. Please add AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT and AZURE_DOCUMENT_INTELLIGENCE_API_KEY to gradle.properties")
                return@withContext null
            }
            
            // Read image from URI
            val imageBytes = readImageFromUri(imageUri)
            val mimeType = determineMimeType(imageUri)
            
            Timber.d("Processing invoice with Azure Document Intelligence: ${imageBytes.size} bytes, mimeType: $mimeType")
            
            // Step 1: Submit document for analysis
            val operationLocation = submitDocumentForAnalysis(imageBytes, mimeType)
            if (operationLocation == null) {
                Timber.e("Failed to submit document for analysis")
                return@withContext null
            }
            
            // Step 2: Poll for results (Azure uses async processing)
            val result = pollForResults(operationLocation)
            if (result == null) {
                Timber.e("Failed to get analysis results")
                return@withContext null
            }
            
            // Step 3: Parse results to ParsedInvoice
            parseResultsToInvoice(result, excludeOwnCompanyNumber, excludeOwnVatNumber)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to process invoice with Azure Document Intelligence")
            null
        }
    }
    
    private suspend fun submitDocumentForAnalysis(imageBytes: ByteArray, mimeType: String): String? = withContext(Dispatchers.IO) {
        var retryCount = 0
        val maxRetries = 3
        val baseDelayMs = 2000L // Start with 2 seconds
        
        while (retryCount <= maxRetries) {
            try {
                val request = Request.Builder()
                    .url(analyzeUrl)
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .addHeader("Content-Type", mimeType)
                    .post(imageBytes.toRequestBody(mimeType.toMediaType()))
                    .build()
                
                Timber.d("Submitting document to Azure: $analyzeUrl (attempt ${retryCount + 1}/${maxRetries + 1})")
                val response = httpClient.newCall(request).execute()
                
                if (response.code == 429) {
                    // Rate limited - retry with exponential backoff
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val delayMs = if (retryAfter != null) {
                        retryAfter * 1000 // Convert seconds to milliseconds
                    } else {
                        baseDelayMs * (2.0.pow(retryCount.toDouble())).toLong() // Exponential backoff
                    }
                    
                    if (retryCount < maxRetries) {
                        Timber.w("Azure rate limited (429). Retrying after ${delayMs}ms...")
                        response.close()
                        delay(delayMs)
                        retryCount++
                        continue
                    } else {
                        val errorBody = response.body?.string()
                        Timber.e("Azure API rate limit exceeded after $maxRetries retries: $errorBody")
                        response.close()
                        return@withContext null
                    }
                }
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Timber.e("Azure API error: ${response.code} - $errorBody")
                    response.close()
                    return@withContext null
                }
                
                // Azure returns operation location in headers
                val operationLocation = response.header("Operation-Location")
                Timber.d("Document submitted, operation location: $operationLocation")
                response.close()
                return@withContext operationLocation
            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    val delayMs = baseDelayMs * (2.0.pow(retryCount.toDouble())).toLong()
                    Timber.w(e, "Failed to submit document, retrying after ${delayMs}ms...")
                    delay(delayMs)
                    retryCount++
                } else {
                    Timber.e(e, "Failed to submit document after $maxRetries retries")
                    return@withContext null
                }
            }
        }
        
        null
    }
    
    private suspend fun pollForResults(operationLocation: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            var attempts = 0
            val maxAttempts = 30  // Wait up to 30 seconds
            
            while (attempts < maxAttempts) {
                val request = Request.Builder()
                    .url(operationLocation)
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .get()
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.code == 429) {
                    // Rate limited - retry with exponential backoff
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val delayMs = if (retryAfter != null) {
                        retryAfter * 1000 // Convert seconds to milliseconds
                    } else {
                        2000L * (2.0.pow(attempts.toDouble())).toLong() // Exponential backoff
                    }
                    
                    Timber.w("Azure rate limited (429) while polling. Waiting ${delayMs}ms...")
                    response.close()
                    delay(delayMs)
                    attempts++
                    continue
                }
                
                if (!response.isSuccessful) {
                    Timber.e("Failed to get results: ${response.code}")
                    response.close()
                    return@withContext null
                }
                
                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Timber.e("Empty response from Azure")
                    return@withContext null
                }
                
                val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
                val status = jsonResponse.get("status")?.asString
                
                when (status) {
                    "succeeded" -> {
                        Timber.d("Analysis completed successfully")
                        return@withContext jsonResponse
                    }
                    "failed" -> {
                        val error = jsonResponse.get("error")?.asJsonObject
                        Timber.e("Analysis failed: $error")
                        return@withContext null
                    }
                    "running", "notStarted" -> {
                        // Still processing, wait and retry
                        attempts++
                        kotlinx.coroutines.delay(1000) // Wait 1 second
                    }
                    else -> {
                        Timber.w("Unknown status: $status")
                        attempts++
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
            
            Timber.e("Timeout waiting for analysis results")
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to poll for results")
            null
        }
    }
    
    private fun parseResultsToInvoice(result: JsonObject, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null): ParsedInvoice {
        var invoiceId: String? = null
        var date: String? = null
        var companyName: String? = null
        var amountNoVat: String? = null
        var vatAmount: String? = null
        var vatNumber: String? = null
        var companyNumber: String? = null
        
        // Azure returns results in analyzeResult.documents[0].fields
        val analyzeResult = result.getAsJsonObject("analyzeResult")
        val documents = analyzeResult?.getAsJsonArray("documents")
        
        if (documents != null && documents.size() > 0) {
            val document = documents[0].asJsonObject
            val fields = document.getAsJsonObject("fields")
            
            if (fields != null) {
                // Map Azure fields to your ParsedInvoice
                fields.get("InvoiceId")?.asJsonObject?.get("valueString")?.asString?.let {
                    invoiceId = it
                }
                
                fields.get("InvoiceDate")?.asJsonObject?.get("valueDate")?.asString?.let {
                    date = normalizeDate(it)
                }
                
                // Vendor/Supplier name
                fields.get("VendorName")?.asJsonObject?.get("valueString")?.asString?.let {
                    val vendorName = it.trim()
                    // Check if VendorName contains company type suffix (UAB, MB, IĮ, AB, etc.)
                    val lowerVendorName = vendorName.lowercase()
                    val hasCompanyType = lowerVendorName.contains("uab") || lowerVendorName.contains("ab") || 
                                        lowerVendorName.contains("mb") || lowerVendorName.contains("iį") ||
                                        lowerVendorName.contains("ltd") || lowerVendorName.contains("oy") || 
                                        lowerVendorName.contains("as") || lowerVendorName.contains("sp")
                    if (hasCompanyType && vendorName.length >= 5) {
                        companyName = vendorName
                        Timber.d("Azure: Extracted company name from VendorName: $companyName")
                    } else {
                        Timber.d("Azure: VendorName '$vendorName' doesn't contain company type suffix, will try text extraction")
                    }
                }
                
                // Subtotal (amount without VAT) - try multiple field names
                if (amountNoVat == null) {
                    fields.get("SubTotal")?.asJsonObject?.get("valueNumber")?.asDouble?.let {
                        amountNoVat = String.format("%.2f", it)
                    }
                }
                if (amountNoVat == null) {
                    fields.get("Subtotal")?.asJsonObject?.get("valueNumber")?.asDouble?.let {
                        amountNoVat = String.format("%.2f", it)
                    }
                }
                if (amountNoVat == null) {
                    fields.get("SubTotal")?.asJsonObject?.get("valueCurrency")?.asJsonObject?.get("amount")?.asDouble?.let {
                        amountNoVat = String.format("%.2f", it)
                    }
                }
                
                // Tax amount (VAT) - try multiple field names
                if (vatAmount == null) {
                    fields.get("TotalTax")?.asJsonObject?.get("valueNumber")?.asDouble?.let {
                        vatAmount = String.format("%.2f", it)
                    }
                }
                if (vatAmount == null) {
                    fields.get("TotalTax")?.asJsonObject?.get("valueCurrency")?.asJsonObject?.get("amount")?.asDouble?.let {
                        vatAmount = String.format("%.2f", it)
                    }
                }
                if (vatAmount == null) {
                    fields.get("Tax")?.asJsonObject?.get("valueNumber")?.asDouble?.let {
                        vatAmount = String.format("%.2f", it)
                    }
                }
                
                // Vendor Tax ID (VAT number) - only accept if it has "LT" prefix
                // If there's no "LT", it's not a VAT number
                // Exclude own company VAT number
                fields.get("VendorTaxId")?.asJsonObject?.get("valueString")?.asString?.let {
                    val vatValue = it.trim().uppercase()
                    // Only accept if it starts with "LT" - otherwise it's not a VAT number
                    // Exclude own company VAT number
                    if (vatValue.startsWith("LT") && (excludeOwnVatNumber == null || !vatValue.equals(excludeOwnVatNumber, ignoreCase = true))) {
                        vatNumber = vatValue
                    }
                }
                
                // Also check alternative field names for invoice ID
                if (invoiceId == null) {
                    fields.get("InvoiceNumber")?.asJsonObject?.get("valueString")?.asString?.let {
                        invoiceId = it
                    }
                }
                
                // Company number - try to extract from text if not in structured fields
                // (Azure doesn't always extract this)
            }
        }
        
        // Also extract text for fallback parsing
        val pages = analyzeResult?.getAsJsonArray("pages")
        val lines = mutableListOf<String>()
        if (pages != null) {
            for (pageElement in pages) {
                val page = pageElement.asJsonObject
                val pageText = page.get("lines")?.asJsonArray
                if (pageText != null) {
                    for (lineElement in pageText) {
                        val line = lineElement.asJsonObject
                        line.get("content")?.asString?.let { lines.add(it) }
                    }
                }
            }
        }
        
        // Always try to extract invoice ID with serial+number combination from text (more accurate)
        // This handles cases where Azure doesn't combine serial and number
        val extractedId = InvoiceParser.extractInvoiceIdWithSerialAndNumber(lines)
        if (extractedId != null) {
            invoiceId = extractedId
            Timber.d("Azure: Extracted Invoice ID with serial+number: $extractedId")
        } else {
            // Fallback to Azure's extracted ID if our extraction failed
            val currentId = invoiceId
            if (currentId == null || currentId.length < 6) {
                Timber.d("Azure: Invoice ID extraction failed, using Azure's extracted ID: $currentId")
            }
        }
        
        // If amounts are still null, or company name doesn't have company type suffix, try to extract from text using local parser
        if (amountNoVat == null || vatAmount == null || companyName == null || 
            !hasCompanyTypeSuffix(companyName)) {
            val parsedFromText = InvoiceParser.parse(lines, excludeOwnCompanyNumber, excludeOwnVatNumber)
            if (amountNoVat == null && parsedFromText.amountWithoutVatEur != null) {
                amountNoVat = parsedFromText.amountWithoutVatEur
                Timber.d("Azure: Extracted amount without VAT from text: $amountNoVat")
            }
            if (vatAmount == null && parsedFromText.vatAmountEur != null) {
                vatAmount = parsedFromText.vatAmountEur
                Timber.d("Azure: Extracted VAT amount from text: $vatAmount")
            }
            // Extract company name from text if not found or doesn't have company type suffix
            if ((companyName == null || !hasCompanyTypeSuffix(companyName)) && parsedFromText.companyName != null) {
                val extractedCompanyName = parsedFromText.companyName
                if (hasCompanyTypeSuffix(extractedCompanyName)) {
                    companyName = extractedCompanyName
                    Timber.d("Azure: Extracted company name from text: $companyName")
                } else {
                    Timber.d("Azure: Extracted company name '$extractedCompanyName' doesn't have company type suffix")
                }
            }
            // Also extract company number from text if not found
            // Ensure it's different from VAT number and own company number
            if (companyNumber == null && parsedFromText.companyNumber != null) {
                val extractedCompanyNo = parsedFromText.companyNumber
                val vatDigits = vatNumber?.removePrefix("LT")?.removePrefix("lt")
                // Only use if different from VAT number and own company number
                if (extractedCompanyNo != vatDigits && (excludeOwnCompanyNumber == null || extractedCompanyNo != excludeOwnCompanyNumber)) {
                    companyNumber = extractedCompanyNo
                    Timber.d("Azure: Extracted company number from text: $companyNumber")
                } else {
                    Timber.d("Azure: Skipped company number '$extractedCompanyNo' (same as VAT number or own company number)")
                }
            }
            // Also extract VAT number from text if not found and exclude own company VAT
            if (vatNumber == null && parsedFromText.vatNumber != null) {
                val extractedVat = parsedFromText.vatNumber
                if (excludeOwnVatNumber == null || !extractedVat.equals(excludeOwnVatNumber, ignoreCase = true)) {
                    vatNumber = extractedVat
                    Timber.d("Azure: Extracted VAT number from text: $vatNumber")
                } else {
                    Timber.d("Azure: Skipped VAT number '$extractedVat' (same as own company VAT number)")
                }
            }
        }
        
        // Final fallback: try advanced company name extraction from text if still not found
        if ((companyName == null || !hasCompanyTypeSuffix(companyName)) && lines.isNotEmpty()) {
            val extractedCompanyName = InvoiceParser.extractCompanyNameAdvanced(lines, companyNumber, vatNumber)
            if (extractedCompanyName != null && hasCompanyTypeSuffix(extractedCompanyName)) {
                companyName = extractedCompanyName
                Timber.d("Azure: Extracted company name using advanced extraction: $companyName")
            }
        }
        
        Timber.d("Azure Extracted - InvoiceID: $invoiceId, Date: $date, Company: $companyName, " +
                "AmountNoVat: $amountNoVat, VatAmount: $vatAmount, " +
                "VatNumber: $vatNumber, CompanyNumber: $companyNumber")
        
        return ParsedInvoice(
            invoiceId = invoiceId,
            date = date,
            companyName = companyName,
            amountWithoutVatEur = amountNoVat,
            vatAmountEur = vatAmount,
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            lines = lines
        )
    }
    
    /**
     * Checks if company name contains Lithuanian company type suffix (UAB, MB, IĮ, AB, etc.)
     */
    private fun hasCompanyTypeSuffix(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase().trim()
        return lower.contains("uab") || lower.contains("ab") || 
               lower.contains("mb") || lower.contains("iį") ||
               lower.contains("ltd") || lower.contains("oy") || 
               lower.contains("as") || lower.contains("sp")
    }
    
    private suspend fun readImageFromUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IllegalStateException("Could not read image from URI: $uri")
    }
    
    private fun determineMimeType(uri: Uri): String {
        val uriString = uri.toString().lowercase()
        return when {
            uriString.endsWith(".png") -> "image/png"
            uriString.endsWith(".jpg") || uriString.endsWith(".jpeg") -> "image/jpeg"
            uriString.endsWith(".pdf") -> "application/pdf"
            else -> "image/jpeg" // Default
        }
    }
    
    private fun normalizeDate(dateStr: String): String? {
        // Azure returns dates in ISO 8601 format: "2025-01-15" or "2025-01-15T00:00:00Z"
        return try {
            val cleaned = dateStr.trim()
            // If already in YYYY-MM-DD format, return as-is
            if (cleaned.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                return cleaned
            }
            // If has time component, extract just the date part
            if (cleaned.contains("T")) {
                return cleaned.substring(0, 10)
            }
            cleaned
        } catch (e: Exception) {
            Timber.w(e, "Failed to normalize date: $dateStr")
            dateStr
        }
    }
}

