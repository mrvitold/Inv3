package com.vitol.inv3.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vitol.inv3.BuildConfig
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.InvoiceRecord
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Exports invoice data to i.SAF 1.2 XML format for Lithuanian VMI (State Tax Inspectorate).
 */
class ISafXmlExporter(private val context: Context) {
    
    companion object {
        private const val NAMESPACE = "http://www.vmi.lt/cms/imas/isaf"
        private const val FILE_VERSION = "iSAF1.2"
        private const val SOFTWARE_COMPANY_NAME = "MB \"Švaros frontas\""
        private const val SOFTWARE_NAME = "LT invoice scanner"
    }
    
    /**
     * Export invoices to i.SAF XML format.
     * 
     * @param invoices List of invoices to export
     * @param ownCompany Own company record (for registration number)
     * @param month Month in format "YYYY-MM" (e.g., "2025-01")
     * @param invoiceType "P" for Purchase, "S" for Sales, or null to determine from invoices
     * @return URI of the exported file
     */
    fun export(
        invoices: List<InvoiceRecord>,
        ownCompany: CompanyRecord?,
        month: String,
        invoiceType: String? = null
    ): Uri {
        if (invoices.isEmpty()) {
            throw IllegalArgumentException("Cannot export empty invoice list")
        }
        
        // Determine invoice type if not provided
        val dataType = invoiceType ?: determineDataType(invoices)
        
        // Parse month to get start and end dates
        val (startDate, endDate) = parseMonthToDates(month)
        
        // Get registration number from own company
        val registrationNumber = ownCompany?.company_number?.toLongOrNull()
            ?: throw IllegalArgumentException("Own company registration number is required")
        
        // Create XML document
        val doc = createXmlDocument(
            invoices = invoices,
            registrationNumber = registrationNumber,
            dataType = dataType,
            startDate = startDate,
            endDate = endDate
        )
        
        // Convert to string
        val xmlString = documentToString(doc)
        
        // Save to file
        val fileName = "isaf_${month}_${dataType}.xml"
        val outFile = File(context.cacheDir, fileName)
        FileOutputStream(outFile).use { 
            it.write(xmlString.toByteArray(Charsets.UTF_8))
        }
        
        // Return URI using FileProvider
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }
    
    /**
     * Save XML file to Downloads folder.
     */
    fun saveToDownloads(
        invoices: List<InvoiceRecord>,
        ownCompany: CompanyRecord?,
        month: String,
        invoiceType: String? = null
    ): String? {
        if (invoices.isEmpty()) {
            return null
        }
        
        val dataType = invoiceType ?: determineDataType(invoices)
        val (startDate, endDate) = parseMonthToDates(month)
        val registrationNumber = ownCompany?.company_number?.toLongOrNull()
            ?: return null
        
        val doc = createXmlDocument(
            invoices = invoices,
            registrationNumber = registrationNumber,
            dataType = dataType,
            startDate = startDate,
            endDate = endDate
        )
        
        val xmlString = documentToString(doc)
        val fileName = "isaf_${month}_${dataType}.xml"
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/xml")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(xmlString.toByteArray(Charsets.UTF_8))
                    }
                    Timber.d("XML file saved to Downloads: $fileName")
                    "Saved to Downloads/$fileName"
                } else {
                    Timber.e("Failed to create file in Downloads")
                    null
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { 
                    it.write(xmlString.toByteArray(Charsets.UTF_8))
                }
                Timber.d("XML file saved to Downloads: ${file.absolutePath}")
                "Saved to Downloads/$fileName"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save XML file to Downloads")
            null
        }
    }
    
    fun determineDataType(invoices: List<InvoiceRecord>): String {
        val types = invoices.mapNotNull { it.invoice_type }.distinct()
        return when {
            types.size == 1 -> types.first()
            types.contains("P") && types.contains("S") -> "F" // Full (both)
            types.contains("P") -> "P"
            types.contains("S") -> "S"
            else -> "P" // Default to Purchase
        }
    }
    
    private fun parseMonthToDates(month: String): Pair<String, String> {
        // month format: "YYYY-MM"
        val parts = month.split("-")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid month format: $month. Expected YYYY-MM")
        }
        
        val year = parts[0].toInt()
        val monthNum = parts[1].toInt()
        
        val calendar = Calendar.getInstance()
        calendar.set(year, monthNum - 1, 1)
        val startDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
        
        // Get last day of month
        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        val endDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
        
        return Pair(startDate, endDate)
    }
    
    private fun createXmlDocument(
        invoices: List<InvoiceRecord>,
        registrationNumber: Long,
        dataType: String,
        startDate: String,
        endDate: String
    ): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()
        
        // Root element
        val root = doc.createElementNS(NAMESPACE, "iSAFFile")
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns", NAMESPACE)
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        doc.appendChild(root)
        
        // Header
        val header = createHeader(doc, registrationNumber, dataType, startDate, endDate)
        root.appendChild(header)
        
        // SourceDocuments
        val sourceDocuments = createSourceDocuments(doc, invoices)
        if (sourceDocuments != null) {
            root.appendChild(sourceDocuments)
        }
        
        return doc
    }
    
    private fun createHeader(
        doc: Document,
        registrationNumber: Long,
        dataType: String,
        startDate: String,
        endDate: String
    ): Element {
        val header = doc.createElementNS(NAMESPACE, "Header")
        val fileDescription = doc.createElementNS(NAMESPACE, "FileDescription")
        
        // FileVersion
        val fileVersion = doc.createElementNS(NAMESPACE, "FileVersion")
        fileVersion.textContent = FILE_VERSION
        fileDescription.appendChild(fileVersion)
        
        // FileDateCreated (xs:dateTime format with UTC timezone)
        val fileDateCreated = doc.createElementNS(NAMESPACE, "FileDateCreated")
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        fileDateCreated.textContent = now
        fileDescription.appendChild(fileDateCreated)
        
        // DataType
        val dataTypeEl = doc.createElementNS(NAMESPACE, "DataType")
        dataTypeEl.textContent = dataType
        fileDescription.appendChild(dataTypeEl)
        
        // SoftwareCompanyName
        val softwareCompanyName = doc.createElementNS(NAMESPACE, "SoftwareCompanyName")
        softwareCompanyName.textContent = SOFTWARE_COMPANY_NAME
        fileDescription.appendChild(softwareCompanyName)
        
        // SoftwareName
        val softwareName = doc.createElementNS(NAMESPACE, "SoftwareName")
        softwareName.textContent = SOFTWARE_NAME
        fileDescription.appendChild(softwareName)
        
        // SoftwareVersion
        val softwareVersion = doc.createElementNS(NAMESPACE, "SoftwareVersion")
        softwareVersion.textContent = BuildConfig.VERSION_NAME
        fileDescription.appendChild(softwareVersion)
        
        // RegistrationNumber
        val registrationNumberEl = doc.createElementNS(NAMESPACE, "RegistrationNumber")
        registrationNumberEl.textContent = registrationNumber.toString()
        fileDescription.appendChild(registrationNumberEl)
        
        // NumberOfParts (default: 1)
        val numberOfParts = doc.createElementNS(NAMESPACE, "NumberOfParts")
        numberOfParts.textContent = "1"
        fileDescription.appendChild(numberOfParts)
        
        // PartNumber
        val partNumber = doc.createElementNS(NAMESPACE, "PartNumber")
        partNumber.textContent = "1"
        fileDescription.appendChild(partNumber)
        
        // SelectionCriteria
        val selectionCriteria = doc.createElementNS(NAMESPACE, "SelectionCriteria")
        val selectionStartDate = doc.createElementNS(NAMESPACE, "SelectionStartDate")
        selectionStartDate.textContent = startDate
        selectionCriteria.appendChild(selectionStartDate)
        
        val selectionEndDate = doc.createElementNS(NAMESPACE, "SelectionEndDate")
        selectionEndDate.textContent = endDate
        selectionCriteria.appendChild(selectionEndDate)
        
        fileDescription.appendChild(selectionCriteria)
        header.appendChild(fileDescription)
        
        return header
    }
    
    private fun createSourceDocuments(doc: Document, invoices: List<InvoiceRecord>): Element? {
        if (invoices.isEmpty()) return null
        
        val sourceDocuments = doc.createElementNS(NAMESPACE, "SourceDocuments")
        
        // Separate invoices by type
        val purchaseInvoices = invoices.filter { it.invoice_type == "P" }
        val salesInvoices = invoices.filter { it.invoice_type == "S" }
        
        // PurchaseInvoices
        if (purchaseInvoices.isNotEmpty()) {
            val purchaseInvoicesEl = doc.createElementNS(NAMESPACE, "PurchaseInvoices")
            purchaseInvoices.forEach { invoice ->
                val invoiceEl = createPurchaseInvoice(doc, invoice)
                purchaseInvoicesEl.appendChild(invoiceEl)
            }
            sourceDocuments.appendChild(purchaseInvoicesEl)
        }
        
        // SalesInvoices
        if (salesInvoices.isNotEmpty()) {
            val salesInvoicesEl = doc.createElementNS(NAMESPACE, "SalesInvoices")
            salesInvoices.forEach { invoice ->
                val invoiceEl = createSalesInvoice(doc, invoice)
                salesInvoicesEl.appendChild(invoiceEl)
            }
            sourceDocuments.appendChild(salesInvoicesEl)
        }
        
        return if (sourceDocuments.hasChildNodes()) sourceDocuments else null
    }
    
    private fun createPurchaseInvoice(doc: Document, invoice: InvoiceRecord): Element {
        val invoiceEl = doc.createElementNS(NAMESPACE, "Invoice")
        
        // InvoiceNo
        val invoiceNo = doc.createElementNS(NAMESPACE, "InvoiceNo")
        invoiceNo.textContent = invoice.invoice_id?.replace(" ", "")?.replace("-", "") ?: "ND"
        invoiceEl.appendChild(invoiceNo)
        
        // SupplierInfo
        val supplierInfo = createSupplierInfo(doc, invoice)
        invoiceEl.appendChild(supplierInfo)
        
        // InvoiceDate (required, cannot be "ND")
        val invoiceDate = doc.createElementNS(NAMESPACE, "InvoiceDate")
        val formattedDate = formatDate(invoice.date)
        if (formattedDate == null) {
            Timber.w("Invoice date is missing for purchase invoice ${invoice.invoice_id}, using current date")
            invoiceDate.textContent = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        } else {
            invoiceDate.textContent = formattedDate
        }
        invoiceEl.appendChild(invoiceDate)
        
        // InvoiceType (default: SF or empty)
        val invoiceType = doc.createElementNS(NAMESPACE, "InvoiceType")
        invoiceType.textContent = "" // Default to SF
        invoiceEl.appendChild(invoiceType)
        
        // SpecialTaxation (default: empty)
        val specialTaxation = doc.createElementNS(NAMESPACE, "SpecialTaxation")
        specialTaxation.textContent = ""
        invoiceEl.appendChild(specialTaxation)
        
        // References (empty)
        val references = doc.createElementNS(NAMESPACE, "References")
        invoiceEl.appendChild(references)
        
        // VATPointDate (required in sequence, nillable - must come before DocumentTotals)
        val vatPointDate = doc.createElementNS(NAMESPACE, "VATPointDate")
        // VATPointDate is optional but must be present in sequence, set as nil if not available
        vatPointDate.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:nil", "true")
        invoiceEl.appendChild(vatPointDate)
        
        // RegistrationAccountDate (required in sequence, nillable - must come before DocumentTotals)
        val registrationAccountDate = doc.createElementNS(NAMESPACE, "RegistrationAccountDate")
        // RegistrationAccountDate is optional but must be present in sequence, set as nil if not available
        registrationAccountDate.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:nil", "true")
        invoiceEl.appendChild(registrationAccountDate)
        
        // DocumentTotals
        val documentTotals = createDocumentTotals(doc, invoice, isPurchase = true)
        invoiceEl.appendChild(documentTotals)
        
        return invoiceEl
    }
    
    private fun createSalesInvoice(doc: Document, invoice: InvoiceRecord): Element {
        val invoiceEl = doc.createElementNS(NAMESPACE, "Invoice")
        
        // InvoiceNo
        val invoiceNo = doc.createElementNS(NAMESPACE, "InvoiceNo")
        invoiceNo.textContent = invoice.invoice_id?.replace(" ", "")?.replace("-", "") ?: "ND"
        invoiceEl.appendChild(invoiceNo)
        
        // CustomerInfo
        val customerInfo = createCustomerInfo(doc, invoice)
        invoiceEl.appendChild(customerInfo)
        
        // InvoiceDate (required for Sales, must be valid date per XSD ISAFDateType3)
        val invoiceDate = doc.createElementNS(NAMESPACE, "InvoiceDate")
        val formattedDate = formatDate(invoice.date)
        if (formattedDate == null) {
            Timber.w("Invoice date is missing for sales invoice ${invoice.invoice_id}, using current date")
            invoiceDate.textContent = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        } else {
            invoiceDate.textContent = formattedDate
        }
        invoiceEl.appendChild(invoiceDate)
        
        // InvoiceType (default: SF or empty)
        val invoiceType = doc.createElementNS(NAMESPACE, "InvoiceType")
        invoiceType.textContent = "" // Default to SF
        invoiceEl.appendChild(invoiceType)
        
        // SpecialTaxation (default: empty)
        val specialTaxation = doc.createElementNS(NAMESPACE, "SpecialTaxation")
        specialTaxation.textContent = ""
        invoiceEl.appendChild(specialTaxation)
        
        // References (empty)
        val references = doc.createElementNS(NAMESPACE, "References")
        invoiceEl.appendChild(references)
        
        // VATPointDate (required in sequence, nillable - must come before DocumentTotals)
        val vatPointDate = doc.createElementNS(NAMESPACE, "VATPointDate")
        // VATPointDate is optional but must be present in sequence, set as nil if not available
        vatPointDate.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:nil", "true")
        invoiceEl.appendChild(vatPointDate)
        
        // DocumentTotals
        val documentTotals = createDocumentTotals(doc, invoice, isPurchase = false)
        invoiceEl.appendChild(documentTotals)
        
        return invoiceEl
    }
    
    private fun createSupplierInfo(doc: Document, invoice: InvoiceRecord): Element {
        val supplierInfo = doc.createElementNS(NAMESPACE, "SupplierInfo")
        
        // VATRegistrationNumber (required, use "ND" if unknown)
        val vatRegistrationNumber = doc.createElementNS(NAMESPACE, "VATRegistrationNumber")
        val vatNumber = normalizeVatNumber(invoice.vat_number)
        vatRegistrationNumber.textContent = vatNumber ?: "ND"
        supplierInfo.appendChild(vatRegistrationNumber)
        
        // RegistrationNumber (optional, only if VAT is "ND")
        if (vatNumber == null || vatNumber == "ND") {
            val registrationNumber = doc.createElementNS(NAMESPACE, "RegistrationNumber")
            registrationNumber.textContent = invoice.company_number ?: "ND"
            supplierInfo.appendChild(registrationNumber)
        }
        
        // Country (required in sequence, nillable - must come before Name)
        // Extract country code from VAT number (first 2 letters, e.g., "LT" from "LT123456789")
        val country = doc.createElementNS(NAMESPACE, "Country")
        val countryCode = if (vatNumber != null && vatNumber != "ND" && vatNumber.length >= 2) {
            val code = vatNumber.substring(0, 2)
            if (code.all { it.isLetter() }) code else null
        } else {
            null
        }
        if (countryCode != null) {
            country.textContent = countryCode
        } else {
            // Set as nil if not available (XSD allows nillable="true")
            country.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:nil", "true")
        }
        supplierInfo.appendChild(country)
        
        // Name (required, use "ND" if unknown) - must come after Country
        val name = doc.createElementNS(NAMESPACE, "Name")
        name.textContent = invoice.company_name ?: "ND"
        supplierInfo.appendChild(name)
        
        return supplierInfo
    }
    
    private fun createCustomerInfo(doc: Document, invoice: InvoiceRecord): Element {
        val customerInfo = doc.createElementNS(NAMESPACE, "CustomerInfo")
        
        // VATRegistrationNumber (required, use "ND" if unknown)
        val vatRegistrationNumber = doc.createElementNS(NAMESPACE, "VATRegistrationNumber")
        val vatNumber = normalizeVatNumber(invoice.vat_number)
        vatRegistrationNumber.textContent = vatNumber ?: "ND"
        customerInfo.appendChild(vatRegistrationNumber)
        
        // RegistrationNumber (optional, only if VAT is "ND")
        if (vatNumber == null || vatNumber == "ND") {
            val registrationNumber = doc.createElementNS(NAMESPACE, "RegistrationNumber")
            registrationNumber.textContent = invoice.company_number ?: "ND"
            customerInfo.appendChild(registrationNumber)
        }
        
        // Country (required in sequence, nillable - must come before Name)
        // Extract country code from VAT number (first 2 letters, e.g., "LT" from "LT123456789")
        val country = doc.createElementNS(NAMESPACE, "Country")
        val countryCode = if (vatNumber != null && vatNumber != "ND" && vatNumber.length >= 2) {
            val code = vatNumber.substring(0, 2)
            if (code.all { it.isLetter() }) code else null
        } else {
            null
        }
        if (countryCode != null) {
            country.textContent = countryCode
        } else {
            // Set as nil if not available (XSD allows nillable="true")
            country.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:nil", "true")
        }
        customerInfo.appendChild(country)
        
        // Name (required, use "ND" if unknown) - must come after Country
        val name = doc.createElementNS(NAMESPACE, "Name")
        name.textContent = invoice.company_name ?: "ND"
        customerInfo.appendChild(name)
        
        return customerInfo
    }
    
    private fun createDocumentTotals(doc: Document, invoice: InvoiceRecord, isPurchase: Boolean): Element {
        val documentTotals = doc.createElementNS(NAMESPACE, "DocumentTotals")
        val documentTotal = doc.createElementNS(NAMESPACE, "DocumentTotal")
        
        // TaxableValue (required)
        val taxableValue = doc.createElementNS(NAMESPACE, "TaxableValue")
        taxableValue.textContent = formatMonetary(invoice.amount_without_vat_eur ?: 0.0)
        documentTotal.appendChild(taxableValue)
        
        // TaxCode (nullable, must match pattern PVM[0-9]* with length 4-6)
        val taxCode = doc.createElementNS(NAMESPACE, "TaxCode")
        val determinedTaxCode = if (!invoice.tax_code.isNullOrBlank() && 
            invoice.tax_code.matches(Regex("^PVM[0-9]+$")) &&
            invoice.tax_code.length in 4..6) {
            invoice.tax_code
        } else {
            // Use TaxCodeDeterminer to determine proper tax code
            TaxCodeDeterminer.determineTaxCode(invoice.vat_rate, null)
        }
        taxCode.textContent = determinedTaxCode
        documentTotal.appendChild(taxCode)
        
        // TaxPercentage (nullable, VAT rate as percentage)
        val taxPercentage = doc.createElementNS(NAMESPACE, "TaxPercentage")
        val vatRate = invoice.vat_rate
        if (vatRate != null && vatRate >= 0) {
            taxPercentage.textContent = formatQuantity(vatRate)
        } else {
            // Calculate from amounts if not set
            val calculatedRate = TaxCodeDeterminer.calculateVatRate(
                invoice.amount_without_vat_eur,
                invoice.vat_amount_eur
            )
            if (calculatedRate != null) {
                taxPercentage.textContent = formatQuantity(calculatedRate)
            }
        }
        documentTotal.appendChild(taxPercentage)
        
        // Amount (nullable, VAT amount)
        val amount = doc.createElementNS(NAMESPACE, "Amount")
        amount.textContent = formatMonetary(invoice.vat_amount_eur ?: 0.0)
        documentTotal.appendChild(amount)
        
        // VATPointDate2 (optional, only for Sales invoices)
        if (!isPurchase) {
            // VATPointDate2 can be added here if we have the data
            // For now, we don't have this field in InvoiceRecord, so we skip it
            // If needed in the future, add invoice.vat_point_date_2 field
        }
        
        documentTotals.appendChild(documentTotal)
        return documentTotals
    }
    
    private fun normalizeVatNumber(vatNumber: String?): String? {
        if (vatNumber.isNullOrBlank()) return null
        // Remove spaces and ensure uppercase
        val normalized = vatNumber.replace(" ", "").uppercase()
        // If doesn't start with country code, assume LT
        return if (normalized.length >= 2 && normalized[0].isLetter() && normalized[1].isLetter()) {
            normalized
        } else {
            "LT$normalized"
        }
    }
    
    private fun formatDate(dateStr: String?): String? {
        if (dateStr.isNullOrBlank()) return null
        
        return try {
            // Try to parse different date formats
            when {
                dateStr.contains("-") -> {
                    // Already in YYYY-MM-DD format
                    if (dateStr.length >= 10) {
                        dateStr.substring(0, 10)
                    } else {
                        dateStr
                    }
                }
                dateStr.contains(".") -> {
                    // DD.MM.YYYY format
                    val parts = dateStr.split(".")
                    if (parts.size == 3) {
                        val day = parts[0].trim().padStart(2, '0')
                        val month = parts[1].trim().padStart(2, '0')
                        val year = parts[2].trim()
                        "$year-$month-$day"
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to format date: $dateStr")
            null
        }
    }
    
    private fun formatMonetary(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }
    
    private fun formatQuantity(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }
    
    private fun documentToString(doc: Document): String {
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no")
        
        val source = DOMSource(doc)
        val writer = StringWriter()
        val result = StreamResult(writer)
        transformer.transform(source, result)
        
        return writer.toString()
    }
}




