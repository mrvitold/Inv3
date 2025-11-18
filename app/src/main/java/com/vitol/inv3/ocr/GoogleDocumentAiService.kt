package com.vitol.inv3.ocr

import android.content.Context
import android.net.Uri
import com.google.auth.oauth2.GoogleCredentials
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.InputStream
import android.util.Base64

/**
 * Service for processing invoices using Google Cloud Document AI REST API.
 * 
 * Setup required:
 * 1. Create a Google Cloud project and enable Document AI API
 * 2. Create a Document AI processor (Invoice Parser)
 * 3. Create a service account and download JSON credentials
 * 4. Place credentials in app/src/main/assets/documentai-credentials.json
 * 5. Update projectId, location, and processorId below
 */
class GoogleDocumentAiService(private val context: Context) {
    
    // Google Cloud Document AI configuration
    private val projectId = "233306881406"  // Your Google Cloud project ID
    private val location = "eu"  // Region where you created the processor
    private val processorId = "eba7c510940e8f9a"  // Your Document AI processor ID
    
    private val processorName = "projects/$projectId/locations/$location/processors/$processorId"
    private val apiEndpoint = "https://${location}-documentai.googleapis.com/v1/$processorName:process"
    
    private val gson = Gson()
    private val httpClient = OkHttpClient()
    
    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val credentials = getCredentials()
            credentials.refreshIfExpired()
            credentials.accessToken?.tokenValue
        } catch (e: Exception) {
            Timber.e(e, "Failed to get access token")
            null
        }
    }
    
    private suspend fun getCredentials(): GoogleCredentials = withContext(Dispatchers.IO) {
        try {
            // Try different possible filenames
            val possibleNames = listOf(
                "documentai-credentials.json",
                "documentai-credentials.json.json"  // In case of double extension
            )
            
            var inputStream: InputStream? = null
            for (name in possibleNames) {
                try {
                    inputStream = context.assets.open(name)
                    Timber.d("Found credentials file: $name")
                    break
                } catch (e: Exception) {
                    // Try next name
                }
            }
            
            if (inputStream == null) {
                throw IllegalStateException("Could not find credentials file. Expected one of: $possibleNames")
            }
            
            GoogleCredentials.fromStream(inputStream)
                .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Google Cloud credentials")
            throw e
        }
    }
    
    /**
     * Process an invoice image using Google Document AI REST API.
     * 
     * @param imageUri URI of the invoice image
     * @return ParsedInvoice with extracted fields, or null if processing failed
     */
    suspend fun processInvoice(imageUri: Uri): ParsedInvoice? = withContext(Dispatchers.IO) {
        try {
            // Validate configuration
            if (projectId == "your-project-id" || processorId == "your-processor-id") {
                Timber.e("Google Document AI not configured. Please update projectId and processorId in GoogleDocumentAiService.kt")
                return@withContext null
            }
            
            // Get access token
            val accessToken = getAccessToken()
            if (accessToken == null) {
                Timber.e("Failed to get access token")
                return@withContext null
            }
            
            // Read image from URI
            val imageBytes = readImageFromUri(imageUri)
            val mimeType = determineMimeType(imageUri)
            
            Timber.d("Processing invoice with Document AI: ${imageBytes.size} bytes, mimeType: $mimeType")
            
            // Encode image to base64 (Android Base64, not Java Base64)
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            
            // Create request body
            val requestBody = JsonObject().apply {
                addProperty("skipHumanReview", true)
                add("rawDocument", JsonObject().apply {
                    addProperty("content", base64Image)
                    addProperty("mimeType", mimeType)
                })
            }
            
            // Create HTTP request
            val request = Request.Builder()
                .url(apiEndpoint)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            // Execute request
            Timber.d("Sending request to Document AI: $apiEndpoint")
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Timber.e("Document AI API error: ${response.code} - $errorBody")
                
                // Check for billing error specifically
                if (response.code == 403 && errorBody != null && errorBody.contains("BILLING_DISABLED")) {
                    Timber.e("Document AI billing is not enabled for project $projectId. " +
                            "Please enable billing at: https://console.developers.google.com/billing/enable?project=$projectId")
                }
                
                return@withContext null
            }
            
            val responseBody = response.body?.string()
            if (responseBody == null) {
                Timber.e("Empty response from Document AI")
                return@withContext null
            }
            
            Timber.d("Document AI response received: ${responseBody.length} bytes")
            
            // Parse response
            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
            val document = jsonResponse.getAsJsonObject("document")
            
            if (document == null) {
                Timber.e("No document in response")
                return@withContext null
            }
            
            // Extract entities
            val entities = document.getAsJsonArray("entities") ?: return@withContext null
            Timber.d("Found ${entities.size()} entities in Document AI response")
            
            // Parse entities to invoice fields
            parseEntitiesToInvoice(entities, document)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to process invoice with Document AI")
            null
        }
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
    
    private fun parseEntitiesToInvoice(entities: com.google.gson.JsonArray, document: com.google.gson.JsonObject): ParsedInvoice {
        var invoiceId: String? = null
        var date: String? = null
        var companyName: String? = null
        var amountNoVat: String? = null
        var vatAmount: String? = null
        var vatNumber: String? = null
        var companyNumber: String? = null
        
        // Map Document AI entities to your ParsedInvoice
        for (entityElement in entities) {
            val entity = entityElement.asJsonObject
            val type = entity.get("type")?.asString ?: continue
            val value = entity.get("mentionText")?.asString ?: continue
            val normalizedType = type.lowercase()
            
            Timber.d("Entity: type=$type, value=$value")
            
            when {
                normalizedType.contains("invoice_id") || 
                normalizedType.contains("invoice_number") || 
                normalizedType.contains("invoice_no") -> {
                    if (invoiceId == null) {
                        invoiceId = value
                        // Try to combine with serial if found separately
                        // Document AI might return them separately, so we'll handle in post-processing
                    }
                }
                normalizedType.contains("invoice_date") || 
                normalizedType.contains("receipt_date") || 
                (normalizedType.contains("date") && !normalizedType.contains("due")) -> {
                    if (date == null) date = normalizeDate(value)
                }
                normalizedType.contains("supplier_name") || 
                normalizedType.contains("vendor_name") || 
                normalizedType.contains("merchant_name") || 
                normalizedType.contains("seller") -> {
                    if (companyName == null) companyName = value
                }
                normalizedType.contains("net_amount") || 
                normalizedType.contains("subtotal") || 
                normalizedType.contains("amount_without_vat") || 
                normalizedType.contains("total_excluding") -> {
                    if (amountNoVat == null) amountNoVat = normalizeAmount(value)
                }
                normalizedType.contains("tax_amount") || 
                normalizedType.contains("vat_amount") || 
                (normalizedType.contains("tax") && !normalizedType.contains("tax_id")) -> {
                    if (vatAmount == null) vatAmount = normalizeAmount(value)
                }
                normalizedType.contains("supplier_vat_id") || 
                normalizedType.contains("vendor_vat_id") || 
                normalizedType.contains("tax_id") || 
                (normalizedType.contains("vat") && normalizedType.contains("id")) -> {
                    if (vatNumber == null) vatNumber = value
                }
                normalizedType.contains("supplier_registration_id") || 
                normalizedType.contains("vendor_registration_id") || 
                normalizedType.contains("registration") -> {
                    if (companyNumber == null) companyNumber = value
                }
            }
        }
        
        // Also extract text for fallback parsing
        val text = document.get("text")?.asString ?: ""
        val lines = text.split("\n").filter { it.isNotBlank() }
        
        // If invoice ID is not found or incomplete, try to extract from text using local parser
        if (invoiceId == null || invoiceId.length < 6) {
            val extractedId = InvoiceParser.extractInvoiceIdWithSerialAndNumber(lines)
            if (extractedId != null) {
                invoiceId = extractedId
                Timber.d("Document AI: Extracted Invoice ID from text fallback: $invoiceId")
            }
        }
        
        Timber.d("Extracted - InvoiceID: $invoiceId, Date: $date, Company: $companyName, " +
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
    
    private fun normalizeDate(dateStr: String): String? {
        // Document AI may return dates in various formats
        // Try to normalize to YYYY-MM-DD format
        return try {
            // Common formats: "2025-01-15", "15/01/2025", "2025-01-15T00:00:00"
            val cleaned = dateStr.trim()
            
            // If already in YYYY-MM-DD format
            if (cleaned.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                return cleaned
            }
            
            // Try parsing with common formats
            val formats = listOf(
                "yyyy-MM-dd",
                "dd/MM/yyyy",
                "MM/dd/yyyy",
                "yyyy/MM/dd",
                "dd-MM-yyyy",
                "MM-dd-yyyy"
            )
            
            for (format in formats) {
                try {
                    val parsed = java.text.SimpleDateFormat(format, java.util.Locale.US).parse(cleaned)
                    if (parsed != null) {
                        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(parsed)
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }
            
            // If all parsing fails, return as-is (might still be useful)
            cleaned
        } catch (e: Exception) {
            Timber.w(e, "Failed to normalize date: $dateStr")
            dateStr
        }
    }
    
    private fun normalizeAmount(amountStr: String): String? {
        // Normalize amount string (remove currency symbols, normalize decimal separator)
        return try {
            var cleaned = amountStr.trim()
                .replace(Regex("[€$£]"), "") // Remove currency symbols
                .replace(" ", "") // Remove spaces
                .replace(",", ".") // Convert comma to dot for decimal
            
            // Validate it's a number
            cleaned.toDoubleOrNull()?.let { 
                // Format to 2 decimal places
                String.format("%.2f", it)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to normalize amount: $amountStr")
            amountStr
        }
    }
}
