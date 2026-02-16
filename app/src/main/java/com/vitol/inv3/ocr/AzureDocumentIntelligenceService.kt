package com.vitol.inv3.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream

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
    private val layoutAnalyzeUrl = "${endpoint}formrecognizer/documentModels/prebuilt-layout:analyze?api-version=$apiVersion"
    private val readAnalyzeUrl = "${endpoint}formrecognizer/documentModels/prebuilt-read:analyze?api-version=$apiVersion"
    
    /** Minimum lines from invoice model below which we call prebuilt-layout for full OCR (same as camera quality). */
    private val minLinesBeforeLayoutFallback = 10
    
    private val gson = Gson()
    private val httpClient = OkHttpClient()
    
    /**
     * Extract invoice data from raw image/PDF bytes. Use this for import flow (no URI).
     * Strategy: prebuilt-layout first (best OCR), then prebuilt-invoice (structured fields), merge.
     */
    suspend fun extractFromBytes(
        imageBytes: ByteArray,
        mimeType: String,
        excludeOwnCompanyNumber: String? = null,
        excludeOwnVatNumber: String? = null,
        excludeOwnCompanyName: String? = null,
        invoiceType: String? = null
    ): ParsedInvoice = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || endpoint.isBlank()) {
            Timber.e("Azure Document Intelligence not configured")
            return@withContext ParsedInvoice(extractionMessage = "OCR not configured. Enter details manually.")
        }
        val (bytes, finalMime) = compressImageIfNeeded(imageBytes, mimeType)
        // 1) Layout for reliable OCR text
        val layoutOp = submitDocumentToUrl(layoutAnalyzeUrl, bytes, finalMime)
        var parsed = if (layoutOp != null) {
            val res = pollForResults(layoutOp)
            if (res != null) parseResultsToInvoice(res, excludeOwnCompanyNumber, excludeOwnVatNumber, excludeOwnCompanyName, invoiceType)
            else ParsedInvoice(extractionMessage = "Could not read document. Try a clearer file or enter manually.")
        } else ParsedInvoice(extractionMessage = "Could not send document. Check connection and try again.")
        // 2) Invoice model for structured fields (VendorName, amounts, etc.)
        val invoiceOp = submitDocumentToUrl(analyzeUrl, bytes, finalMime)
        if (invoiceOp != null) {
            val invRes = pollForResults(invoiceOp)
            if (invRes != null) {
                val invoiceParsed = parseResultsToInvoice(invRes, excludeOwnCompanyNumber, excludeOwnVatNumber, excludeOwnCompanyName, invoiceType)
                parsed = mergeParsedInvoices(invoiceParsed, parsed)
            }
        }
        // 3) If still no text, try prebuilt-read
        if (parsed.lines.isEmpty()) {
            val readOp = submitDocumentToUrl(readAnalyzeUrl, bytes, finalMime)
            if (readOp != null) {
                val readRes = pollForResults(readOp)
                if (readRes != null) {
                    val readParsed = parseResultsToInvoice(readRes, excludeOwnCompanyNumber, excludeOwnVatNumber, excludeOwnCompanyName, invoiceType)
                    if (readParsed.lines.isNotEmpty()) parsed = mergeParsedInvoices(parsed, readParsed)
                }
            }
        }
        parsed
    }

    /**
     * Process an invoice from a URI (camera flow). Reads bytes and delegates to extractFromBytes.
     */
    suspend fun processInvoice(imageUri: Uri, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null, excludeOwnCompanyName: String? = null, invoiceType: String? = null): ParsedInvoice? = withContext(Dispatchers.IO) {
        try {
            val originalBytes = readImageFromUri(imageUri)
            val mimeType = determineMimeType(imageUri)
            extractFromBytes(originalBytes, mimeType, excludeOwnCompanyNumber, excludeOwnVatNumber, excludeOwnCompanyName, invoiceType)
        } catch (e: Exception) {
            Timber.e(e, "Failed to process invoice from URI")
            null
        }
    }
    
    private suspend fun submitDocumentToUrl(url: String, imageBytes: ByteArray, mimeType: String): String? = withContext(Dispatchers.IO) {
        var retryCount = 0
        val maxRetries = 3
        val baseDelayMs = 2000L // Start with 2 seconds
        
        while (retryCount <= maxRetries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .addHeader("Content-Type", mimeType)
                    .post(imageBytes.toRequestBody(mimeType.toMediaType()))
                    .build()
                
                Timber.d("Submitting document to Azure: $url (attempt ${retryCount + 1}/${maxRetries + 1})")
                val response = httpClient.newCall(request).execute()
                
                if (response.code == 429) {
                    // Rate limited - retry with backoff. Cap Retry-After at 15s to avoid very long waits
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val delayMs = if (retryAfter != null) {
                        minOf(retryAfter * 1000, 15_000L) // Cap at 15 seconds
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
                    // Rate limited - retry with backoff. Cap Retry-After at 15s to avoid very long waits
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val delayMs = if (retryAfter != null) {
                        minOf(retryAfter * 1000, 15_000L) // Cap at 15 seconds
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
    
    /** Merges invoice-model result with layout-model result: prefer non-blank from invoice, fill from layout; use layout lines (full OCR). */
    private fun mergeParsedInvoices(fromInvoice: ParsedInvoice, fromLayout: ParsedInvoice): ParsedInvoice = ParsedInvoice(
        invoiceId = fromInvoice.invoiceId.takeIf { !it.isNullOrBlank() } ?: fromLayout.invoiceId,
        date = fromInvoice.date.takeIf { !it.isNullOrBlank() } ?: fromLayout.date,
        companyName = fromInvoice.companyName.takeIf { !it.isNullOrBlank() } ?: fromLayout.companyName,
        amountWithoutVatEur = fromInvoice.amountWithoutVatEur.takeIf { !it.isNullOrBlank() } ?: fromLayout.amountWithoutVatEur,
        vatAmountEur = fromInvoice.vatAmountEur.takeIf { !it.isNullOrBlank() } ?: fromLayout.vatAmountEur,
        vatNumber = fromInvoice.vatNumber.takeIf { !it.isNullOrBlank() } ?: fromLayout.vatNumber,
        companyNumber = fromInvoice.companyNumber.takeIf { !it.isNullOrBlank() } ?: fromLayout.companyNumber,
        vatRate = fromInvoice.vatRate.takeIf { !it.isNullOrBlank() } ?: fromLayout.vatRate,
        lines = fromLayout.lines,
        extractionMessage = if (fromLayout.lines.isNotEmpty()) null else (fromInvoice.extractionMessage ?: fromLayout.extractionMessage)
    )
    
    private fun parseResultsToInvoice(result: JsonObject, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null, excludeOwnCompanyName: String? = null, invoiceType: String? = null): ParsedInvoice {
        var invoiceId: String? = null
        var date: String? = null
        var companyName: String? = null
        var amountNoVat: String? = null
        var vatAmount: String? = null
        var vatNumber: String? = null
        var companyNumber: String? = null
        
        // Azure returns results in analyzeResult.documents[0].fields (or "result" in some API versions)
        val analyzeResult = result.getAsJsonObject("analyzeResult") ?: result.getAsJsonObject("result")
        val documents = analyzeResult?.getAsJsonArray("documents")
        
        // Helper: get string from a field object (API may use valueString or content)
        fun fieldString(field: com.google.gson.JsonElement?): String? {
            val obj = field?.asJsonObject ?: return null
            return obj.get("valueString")?.asString ?: obj.get("content")?.asString
        }
        // Helper: get number from a field (valueNumber, valueCurrency.amount, or parse content)
        fun fieldNumber(field: com.google.gson.JsonElement?): Double? {
            val obj = field?.asJsonObject ?: return null
            obj.get("valueNumber")?.asDouble?.let { return it }
            obj.get("valueCurrency")?.asJsonObject?.get("amount")?.asDouble?.let { return it }
            fieldString(field)?.toDoubleOrNull()?.let { return it }
            return null
        }
        
        if (documents != null && documents.size() > 0) {
            val document = documents[0].asJsonObject
            val fields = document.getAsJsonObject("fields")
            
            if (fields != null) {
                // Map Azure fields to your ParsedInvoice (use valueString or content)
                fieldString(fields.get("InvoiceId"))?.let { invoiceId = it }
                
                (fields.get("InvoiceDate")?.asJsonObject?.get("valueDate")?.asString
                    ?: fieldString(fields.get("InvoiceDate")))?.let { date = normalizeDate(it) }
                
                // Determine if this is a sales invoice
                val isSalesInvoice = invoiceType == "S"
                
                // For sales invoices: prioritize CustomerName (buyer), exclude VendorName (seller = own company)
                // For purchase invoices: prioritize VendorName (seller), exclude CustomerName (buyer = own company)
                // Use vendor/customer name even without UAB/AB/etc. so names like "KESKO", "SENUKAI" fill the form
                if (isSalesInvoice) {
                    // SALES: customer = buyer (partner), vendor = seller (often own company)
                    fieldString(fields.get("CustomerName"))?.let {
                        val name = it.trim()
                        if (name.length >= 2 && (excludeOwnCompanyName == null || !CompanyNameUtils.isSameAsOwnCompanyName(name, excludeOwnCompanyName))) {
                            companyName = name
                            Timber.d("Azure: [SALES] Extracted company name from CustomerName: $name")
                        }
                    }
                    if (companyName == null) {
                        fieldString(fields.get("VendorName"))?.let {
                            val name = it.trim()
                            if (name.length >= 2 && (excludeOwnCompanyName == null || !CompanyNameUtils.isSameAsOwnCompanyName(name, excludeOwnCompanyName))) {
                                companyName = name
                                Timber.d("Azure: [SALES] Fallback: Extracted company name from VendorName: $name")
                            }
                        }
                    }
                } else {
                    // PURCHASE: vendor = seller (partner), customer = buyer (often own company)
                    fieldString(fields.get("VendorName"))?.let {
                        val name = it.trim()
                        if (name.length >= 2 && (excludeOwnCompanyName == null || !CompanyNameUtils.isSameAsOwnCompanyName(name, excludeOwnCompanyName))) {
                            companyName = name
                            Timber.d("Azure: [PURCHASE] Extracted company name from VendorName: $name")
                        }
                    }
                    if (companyName == null) {
                        fieldString(fields.get("CustomerName"))?.let {
                            val name = it.trim()
                            if (name.length >= 2 && (excludeOwnCompanyName == null || !CompanyNameUtils.isSameAsOwnCompanyName(name, excludeOwnCompanyName))) {
                                companyName = name
                                Timber.d("Azure: [PURCHASE] Fallback: Extracted company name from CustomerName: $name")
                            }
                        }
                    }
                }
                
                // Subtotal (amount without VAT) - try multiple field names
                if (amountNoVat == null) {
                    fieldNumber(fields.get("SubTotal"))?.let { amountNoVat = String.format("%.2f", it) }
                }
                if (amountNoVat == null) {
                    fieldNumber(fields.get("Subtotal"))?.let { amountNoVat = String.format("%.2f", it) }
                }
                
                // Tax amount (VAT) - try multiple field names
                if (vatAmount == null) {
                    fieldNumber(fields.get("TotalTax"))?.let { vatAmount = String.format("%.2f", it) }
                }
                if (vatAmount == null) {
                    fieldNumber(fields.get("Tax"))?.let { vatAmount = String.format("%.2f", it) }
                }
                // Total / InvoiceTotal as fallback for amount if no subtotal
                if (amountNoVat == null) {
                    fieldNumber(fields.get("InvoiceTotal"))?.let { amountNoVat = String.format("%.2f", it) }
                }
                if (amountNoVat == null) {
                    fieldNumber(fields.get("Total"))?.let { amountNoVat = String.format("%.2f", it) }
                }
                
                // VAT number extraction - prioritize based on invoice type
                if (isSalesInvoice) {
                    // SALES INVOICE: Extract customer VAT (buyer), exclude vendor VAT (seller = own company)
                    fieldString(fields.get("CustomerTaxId"))?.let {
                        val vatValue = it.trim().uppercase()
                        // Only accept if it starts with "LT" and exclude own company VAT number
                        if (vatValue.startsWith("LT") && (excludeOwnVatNumber == null || !vatValue.equals(excludeOwnVatNumber, ignoreCase = true))) {
                            vatNumber = vatValue
                            Timber.d("Azure: [SALES] Extracted VAT number from CustomerTaxId: $vatNumber")
                        } else {
                            Timber.d("Azure: [SALES] Skipped CustomerTaxId '$vatValue' (no LT prefix or matches own company)")
                        }
                    }
                    // Fallback to VendorTaxId only if CustomerTaxId wasn't found (shouldn't happen for sales invoices)
                    if (vatNumber == null) {
                        fieldString(fields.get("VendorTaxId"))?.let {
                            val vatValue = it.trim().uppercase()
                            // Exclude own company VAT number - for sales invoices, vendor is usually own company
                            if (vatValue.startsWith("LT") && (excludeOwnVatNumber == null || !vatValue.equals(excludeOwnVatNumber, ignoreCase = true))) {
                                vatNumber = vatValue
                                Timber.d("Azure: [SALES] Fallback: Extracted VAT number from VendorTaxId: $vatNumber")
                            } else {
                                Timber.d("Azure: [SALES] Skipped VendorTaxId '$vatValue' (no LT prefix or matches own company)")
                            }
                        }
                    }
                } else {
                    // PURCHASE INVOICE: Extract vendor VAT (seller), exclude customer VAT (buyer = own company)
                    fieldString(fields.get("VendorTaxId"))?.let {
                        val vatValue = it.trim().uppercase()
                        // Only accept if it starts with "LT" and exclude own company VAT number
                        if (vatValue.startsWith("LT") && (excludeOwnVatNumber == null || !vatValue.equals(excludeOwnVatNumber, ignoreCase = true))) {
                            vatNumber = vatValue
                            Timber.d("Azure: [PURCHASE] Extracted VAT number from VendorTaxId: $vatNumber")
                        } else {
                            Timber.d("Azure: [PURCHASE] Skipped VendorTaxId '$vatValue' (no LT prefix or matches own company)")
                        }
                    }
                    // Fallback to CustomerTaxId only if VendorTaxId wasn't found
                    if (vatNumber == null) {
                        fieldString(fields.get("CustomerTaxId"))?.let {
                            val vatValue = it.trim().uppercase()
                            // Only accept if it starts with "LT" and exclude own company VAT number
                            if (vatValue.startsWith("LT") && (excludeOwnVatNumber == null || !vatValue.equals(excludeOwnVatNumber, ignoreCase = true))) {
                                vatNumber = vatValue
                                Timber.d("Azure: [PURCHASE] Fallback: Extracted VAT number from CustomerTaxId: $vatNumber")
                            } else {
                                Timber.d("Azure: [PURCHASE] Skipped CustomerTaxId '$vatValue' (no LT prefix or matches own company)")
                            }
                        }
                    }
                }
                
                // Also check alternative field names for invoice ID
                if (invoiceId == null) {
                    fieldString(fields.get("InvoiceNumber"))?.let { invoiceId = it }
                }
                
                // Company number - try to extract from text if not in structured fields
                // (Azure doesn't always extract this)
            }
        }
        
        // Extract text for InvoiceParser. Azure can return full OCR in "content" OR in pages[].lines;
        // for some documents (e.g. Kesko receipt PDF) "content" is only the first block (e.g. "KESKO SENUKAI")
        // while pages[].lines has the full page text. So we use whichever source has more lines.
        val pages = analyzeResult?.getAsJsonArray("pages")
        val linesFromContent = mutableListOf<String>()
        val contentStr = analyzeResult?.get("content")?.asString ?: result.get("content")?.asString
        if (!contentStr.isNullOrBlank()) {
            var fromContent = contentStr.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            if (fromContent.size == 1 && fromContent[0].length > 300) {
                val singleLine = fromContent[0]
                fromContent = singleLine.split(Regex("\\s{2,}|\u00a0+")).map { it.trim() }.filter { it.isNotEmpty() }
                Timber.d("Azure: Split single long line (${singleLine.length} chars) into ${fromContent.size} lines by spaces")
            }
            if (fromContent.isNotEmpty()) {
                linesFromContent.addAll(fromContent)
            }
        }
        val linesFromPages = mutableListOf<String>()
        if (pages != null) {
            for (pageElement in pages) {
                val page = pageElement.asJsonObject
                val pageLines = page.get("lines")?.asJsonArray
                if (pageLines != null) {
                    for (lineElement in pageLines) {
                        val lineObj = lineElement.asJsonObject
                        (lineObj.get("content")?.asString ?: lineObj.get("text")?.asString)?.trim()?.takeIf { it.isNotEmpty() }?.let { linesFromPages.add(it) }
                    }
                }
            }
        }
        val lines = when {
            linesFromPages.size > linesFromContent.size -> {
                Timber.d("Azure: Using ${linesFromPages.size} lines from pages[].lines (content had ${linesFromContent.size})")
                linesFromPages.toMutableList()
            }
            linesFromContent.isNotEmpty() -> {
                Timber.d("Azure: Using ${linesFromContent.size} lines from content")
                linesFromContent.toMutableList()
            }
            linesFromPages.isNotEmpty() -> linesFromPages.toMutableList()
            else -> mutableListOf<String>()
        }
        // Fallback: use paragraphs[].content if present
        if (lines.isEmpty()) {
            val paragraphs = analyzeResult?.getAsJsonArray("paragraphs")
            if (paragraphs != null) {
                for (p in paragraphs) {
                    val para = p.asJsonObject
                    (para.get("content")?.asString?.trim())?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                }
                if (lines.isNotEmpty()) {
                    Timber.d("Azure: Extracted ${lines.size} lines from analyzeResult.paragraphs")
                }
            }
        }
        // Fallback: build lines from pages[].words (OCR words when lines array is empty)
        if (lines.isEmpty() && pages != null) {
            for (pageElement in pages) {
                val page = pageElement.asJsonObject
                val words = page.get("words")?.asJsonArray
                if (words != null && words.size() > 0) {
                    val pageWords = words.mapNotNull { w ->
                        (w.asJsonObject.get("content")?.asString ?: w.asJsonObject.get("text")?.asString)?.trim()
                    }.filter { it.isNotEmpty() }
                    if (pageWords.isNotEmpty()) {
                        // One line per page: join words with space (preserves order for parser)
                        lines.add(pageWords.joinToString(" "))
                        Timber.d("Azure: Built line from page words (${pageWords.size} words)")
                    }
                }
            }
            if (lines.isNotEmpty()) {
                Timber.d("Azure: Extracted ${lines.size} lines from pages[].words")
            }
        }
        // Fallback: build lines from document fields (content/valueString) so parser can run
        if (lines.isEmpty() && documents != null && documents.size() > 0) {
            val document = documents[0].asJsonObject
            val fields = document.getAsJsonObject("fields")
            if (fields != null) {
                for (entry in fields.entrySet()) {
                    val fieldObj = entry.value?.asJsonObject ?: continue
                    val text = fieldObj.get("content")?.asString ?: fieldObj.get("valueString")?.asString
                        ?: fieldObj.get("valueDate")?.asString
                        ?: fieldObj.get("valueNumber")?.let { n -> n.asNumber.toString() }
                    text?.trim()?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                }
                if (lines.isNotEmpty()) {
                    Timber.d("Azure: Built ${lines.size} lines from document fields (no OCR text in result)")
                }
            }
        }
        val emptyExtractionMessage = "No text could be read from this page. Try a clearer scan or enter details manually."
        if (lines.isEmpty()) {
            val content = analyzeResult?.get("content")?.asString ?: result.get("content")?.asString
            val contentLen = content?.length ?: 0
            val paragraphCount = analyzeResult?.getAsJsonArray("paragraphs")?.size() ?: 0
            val docCount = documents?.size() ?: 0
            val hasFields = documents?.let { it.size() > 0 && it[0].asJsonObject.getAsJsonObject("fields") != null } ?: false
            Timber.w("Azure: No text lines in result. content.length=$contentLen, paragraphs=$paragraphCount, analyzeResult keys=${analyzeResult?.keySet()}, documents.size=$docCount, hasFields=$hasFields")
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
        
        // Always try to extract from text using local parser for missing fields or to improve accuracy
        // This is especially important for sales invoices where we need to extract buyer info
        // Pass invoice type so parser can prioritize buyer vs seller sections correctly
        val parsedFromText = InvoiceParser.parse(lines, excludeOwnCompanyNumber, excludeOwnVatNumber, excludeOwnCompanyName, invoiceType)
        
        // Extract amounts if not found
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
                // Exclude own company name (fuzzy match handles variations like "MB Švaros frontas" vs "Švaros frontas")
                if (excludeOwnCompanyName == null || !CompanyNameUtils.isSameAsOwnCompanyName(extractedCompanyName, excludeOwnCompanyName)) {
                    companyName = extractedCompanyName
                    Timber.d("Azure: Extracted company name from text: $companyName")
                } else {
                    Timber.d("Azure: Skipped company name '$extractedCompanyName' from text (matches own company name)")
                }
            } else {
                Timber.d("Azure: Extracted company name '$extractedCompanyName' doesn't have company type suffix")
            }
        }
        
        // Always try to extract company number from text if not found
        // This is critical for sales invoices where Azure might not extract buyer's company number
        // IMPORTANT: Extract company number even if we already have one, to ensure we get the partner's number
        val extractedCompanyNo = parsedFromText.companyNumber
        if (extractedCompanyNo != null) {
            val vatDigits = vatNumber?.removePrefix("LT")?.removePrefix("lt")
            // Only use if different from VAT number and own company number
            if (extractedCompanyNo != vatDigits && (excludeOwnCompanyNumber == null || extractedCompanyNo != excludeOwnCompanyNumber)) {
                // Always update company number if extracted one is valid (even if we had one before)
                // This ensures we get the partner's company number, not our own
                companyNumber = extractedCompanyNo
                Timber.d("Azure: Extracted company number from text: $companyNumber")
            } else {
                Timber.d("Azure: Skipped company number '$extractedCompanyNo' (same as VAT number '$vatDigits' or own company number '$excludeOwnCompanyNumber')")
                // If we skipped it but don't have a company number yet, keep trying
                if (companyNumber == null) {
                    Timber.d("Azure: Company number is null, but extracted '$extractedCompanyNo' was skipped. This might be an issue.")
                }
            }
        } else {
            Timber.d("Azure: No company number extracted from text parsing. Parsed result companyNumber: ${parsedFromText.companyNumber}")
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
        
        // Final fallback: try advanced company name extraction from text if still not found
        if ((companyName == null || !hasCompanyTypeSuffix(companyName)) && lines.isNotEmpty()) {
            val extractedCompanyName = InvoiceParser.extractCompanyNameAdvanced(lines, companyNumber, vatNumber, excludeOwnCompanyNumber, excludeOwnCompanyName, invoiceType)
            if (extractedCompanyName != null && hasCompanyTypeSuffix(extractedCompanyName)) {
                companyName = extractedCompanyName
                Timber.d("Azure: Extracted company name using advanced extraction: $companyName")
            }
        }
        
        // When Azure returns very little OCR, use first lines as company name (same logic as when document fields exist)
        // Prefer a line or joined lines that contain legal suffix (UAB, AB, etc.) so "UAB" is not dropped
        if (companyName.isNullOrBlank() && lines.isNotEmpty()) {
            val trimmed = lines.map { it.trim() }.filter { it.isNotEmpty() }
            var candidate: String? = null
            for (n in 1..minOf(5, trimmed.size)) {
                val joined = trimmed.take(n).joinToString(" ").trim()
                if (joined.length >= 2 && hasCompanyTypeSuffix(joined)) {
                    candidate = joined
                    break
                }
            }
            if (candidate == null && trimmed.isNotEmpty()) {
                candidate = trimmed.take(3).joinToString(" ").trim()
            }
            if (!candidate.isNullOrBlank() &&
                (excludeOwnCompanyName == null || !CompanyNameUtils.isSameAsOwnCompanyName(candidate, excludeOwnCompanyName))) {
                companyName = candidate
                Timber.d("Azure: Using first line(s) as company name (no structured data): $companyName")
            }
        }
        
        // Extract VAT rate from text if available
        var vatRate: String? = parsedFromText.vatRate
        
        Timber.d("Azure Extracted - InvoiceID: $invoiceId, Date: $date, Company: $companyName, " +
                "AmountNoVat: $amountNoVat, VatAmount: $vatAmount, " +
                "VatNumber: $vatNumber, CompanyNumber: $companyNumber, VatRate: $vatRate")
        
        return ParsedInvoice(
            invoiceId = invoiceId,
            date = date,
            companyName = companyName,
            amountWithoutVatEur = amountNoVat,
            vatAmountEur = vatAmount,
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            vatRate = vatRate,
            lines = lines,
            extractionMessage = if (lines.isEmpty()) emptyExtractionMessage else null
        )
    }
    
    /**
     * Checks if company name contains Lithuanian company type suffix (UAB, MB, IĮ, AB, etc.)
     * Excludes "as" when part of "centas" (cents) - that indicates amount-in-words, not company.
     */
    private fun hasCompanyTypeSuffix(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase().trim()
        if (lower.contains("eurai") && lower.contains("centas")) return false // Amount-in-words
        return lower.contains("uab") || lower.contains("ab") ||
               lower.contains("mb") || lower.contains("iį") ||
               lower.contains("ltd") || lower.contains("oy") ||
               (lower.contains("as") && !lower.contains("centas")) || lower.contains("sp")
    }
    
    private suspend fun readImageFromUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IllegalStateException("Could not read image from URI: $uri")
    }
    
    /**
     * Compress image if it exceeds size limits for Azure Document Intelligence.
     * Returns Pair of (compressed bytes, mime type) - mime type will be "image/jpeg" if compressed.
     */
    private suspend fun compressImageIfNeeded(originalBytes: ByteArray, mimeType: String): Pair<ByteArray, String> =
        compressImageIfNeeded(originalBytes, Uri.EMPTY, mimeType)

    @Suppress("UNUSED_PARAMETER")
    private suspend fun compressImageIfNeeded(originalBytes: ByteArray, uri: Uri, mimeType: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val maxFileSizeBytes = 3 * 1024 * 1024 // 3 MB
        val maxDimension = 3000 // Max width or height in pixels
        
        // If already small enough, return as-is
        if (originalBytes.size <= maxFileSizeBytes) {
            // Still check dimensions - might be high resolution but compressed
            val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            if (bitmap != null) {
                val maxDim = maxOf(bitmap.width, bitmap.height)
                if (maxDim <= maxDimension) {
                    bitmap.recycle()
                    return@withContext Pair(originalBytes, mimeType)
                }
                bitmap.recycle()
            } else {
                return@withContext Pair(originalBytes, mimeType)
            }
        }
        
        Timber.d("Image too large (${originalBytes.size} bytes), compressing...")
        
        // Decode bitmap
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options)
        
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        val maxOriginalDim = maxOf(originalWidth, originalHeight)
        
        // Calculate sample size for resizing
        val sampleSize = if (maxOriginalDim > maxDimension) {
            (maxOriginalDim / maxDimension).coerceAtLeast(1)
        } else {
            1
        }
        
        Timber.d("Original dimensions: ${originalWidth}x${originalHeight}, sampleSize: $sampleSize")
        
        // Decode with sample size
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions)
            ?: return@withContext Pair(originalBytes, mimeType)
        
        try {
            // Compress to JPEG with quality adjustment
            val outputStream = ByteArrayOutputStream()
            var quality = 90
            var compressedBytes: ByteArray
            
            do {
                outputStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressedBytes = outputStream.toByteArray()
                
                if (compressedBytes.size <= maxFileSizeBytes) {
                    break
                }
                
                // Reduce quality by 10% each iteration
                quality -= 10
            } while (quality >= 50 && compressedBytes.size > maxFileSizeBytes)
            
            Timber.d("Compressed image: ${compressedBytes.size} bytes (quality: $quality)")
            
            // If still too large after compression, resize further
            if (compressedBytes.size > maxFileSizeBytes && quality >= 50) {
                val scaleFactor = kotlin.math.sqrt(maxFileSizeBytes.toDouble() / compressedBytes.size)
                val newWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1000)
                val newHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1000)
                
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                outputStream.reset()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                compressedBytes = outputStream.toByteArray()
                resizedBitmap.recycle()
                
                Timber.d("Further resized to ${newWidth}x${newHeight}, final size: ${compressedBytes.size} bytes")
            }
            
            // Return compressed bytes with JPEG mime type (since we compressed to JPEG)
            Pair(compressedBytes, "image/jpeg")
        } finally {
            bitmap.recycle()
        }
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

