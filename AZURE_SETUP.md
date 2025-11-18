# Azure Document Intelligence Integration - Setup Guide

## ✅ Implementation Complete

The Azure Document Intelligence integration has been successfully implemented in your app!

## 📋 What Was Changed

1. **Created** `app/src/main/java/com/vitol/inv3/ocr/AzureDocumentIntelligenceService.kt`
   - New service class for Azure Document Intelligence API
   - Handles async document processing (submit → poll → get results)
   - Maps Azure fields to your `ParsedInvoice` format

2. **Updated** `app/src/main/java/com/vitol/inv3/ui/review/ReviewScreen.kt`
   - Replaced `GoogleDocumentAiService` with `AzureDocumentIntelligenceService`
   - Updated UI indicator to show "Azure" instead of "Google"
   - Updated all logging messages to reference Azure

## 🔧 Final Step: Add Your API Key

**IMPORTANT:** You need to add your Azure API key before the service will work.

1. **Get your API key from Azure Portal:**
   - Go to [Azure Portal](https://portal.azure.com)
   - Open your Document Intelligence resource (svarosfrontas)
   - Click "Keys and Endpoint" in the left menu
   - Copy **Key 1** (or Key 2)

2. **Update the service file:**
   - Open `app/src/main/java/com/vitol/inv3/ocr/AzureDocumentIntelligenceService.kt`
   - Find line 22: `private val apiKey = "YOUR_API_KEY_HERE"`
   - Replace `YOUR_API_KEY_HERE` with your actual API key
   - Example: `private val apiKey = "abc123def456..."`

3. **Your endpoint is already configured:**
   - Endpoint: `https://svarosfrontas.cognitiveservices.azure.com/`
   - This is already set in the code (line 21)

## 🧪 Testing

After adding your API key:

1. Build and run the app
2. Take a photo of an invoice
3. The app will:
   - Try Azure Document Intelligence first
   - Show "Azure" badge in top-right corner if successful
   - Fall back to local OCR if Azure fails
   - Show "Local" badge if using local OCR

4. Check Logcat for messages:
   - "Processing invoice with Azure Document Intelligence"
   - "Analysis completed successfully"
   - "Azure Extracted - InvoiceID: ..."

## 💰 Cost Information

- **Free Tier:** 500 pages/month FREE
- **After Free Tier:** €0.001287 per invoice (€1.287 per 1,000 invoices)
- **Comparison:** 
  - Google: €0.086 per invoice
  - Azure: €0.001287 per invoice (66x cheaper!)

## 🔍 Troubleshooting

### Error: "Azure Document Intelligence not configured"
- **Solution:** Make sure you've replaced `YOUR_API_KEY_HERE` with your actual API key

### Error: "Azure API error: 401"
- **Solution:** Your API key is incorrect. Double-check it in Azure Portal

### Error: "Azure API error: 404"
- **Solution:** Check that your endpoint URL is correct and includes the trailing slash

### Always falls back to local OCR
- Check Logcat for specific error messages
- Verify your API key is correct
- Make sure you have internet connection
- Check Azure Portal for any service issues

## 📝 Field Mapping

Azure Document Intelligence extracts these fields:
- **InvoiceId** → Invoice_ID
- **InvoiceDate** → Date
- **VendorName** → Company_name
- **SubTotal** → Amount_without_VAT_EUR
- **TotalTax** → VAT_amount_EUR
- **VendorTaxId** → VAT_number
- **Company_number** → Extracted from text (fallback)

## 🎯 Next Steps

1. ✅ Add your API key to `AzureDocumentIntelligenceService.kt`
2. ✅ Test with a sample invoice
3. ✅ Monitor accuracy and costs in Azure Portal
4. ✅ (Optional) Remove Google dependencies if you no longer need them

## 🔐 Security Note

⚠️ **Important:** The API key is currently hardcoded in the source file. For production:
- Consider storing it in `gradle.properties` (not committed to git)
- Or use a backend server to handle API calls
- The key is already in `.gitignore` for the credentials file, but the source code will be committed

---

**Status:** ✅ Ready to use (just add your API key!)

