# i.SAF XML Format Requirements

## Header Section

1. **FileVersion**: `"iSAF1.2"` (fixed value)
2. **FileDateCreated**: `xs:dateTime` format: `YYYY-MM-DDTHH:mm:ss` (ISO 8601)
3. **DataType**: `"F"` (Full), `"S"` (Sales), or `"P"` (Purchase)
4. **SoftwareCompanyName**: Max 256 characters
5. **SoftwareName**: Max 256 characters
6. **SoftwareVersion**: Max 24 characters
7. **RegistrationNumber**: Unsigned long, max 11 digits (taxpayer ID = company number)
8. **NumberOfParts**: Unsigned integer, nullable (default: 1 if not split)
9. **PartNumber**: Pattern `[A-Z0-9_]*`, 1-20 characters (default: "1")
10. **SelectionStartDate**: Date format `YYYY-MM-DD`, min: 2016-07-01, max: 2100-01-01
11. **SelectionEndDate**: Date format `YYYY-MM-DD`, min: 2016-10-01, max: 2100-01-01

## Invoice Fields

### Common Fields
- **InvoiceNo**: Max 70 characters, min 1 (recommended: no spaces/dashes)
- **InvoiceDate**: Date format `YYYY-MM-DD`
  - Purchase: `xs:date` (any valid date)
  - Sales: `ISAFDateType3` (min: 2016-07-01, must be within tax period)
- **InvoiceType**: `"SF"` (default/empty), `"DS"`, `"KS"`, `"VS"`, `"VD"`, `"VK"`, `"AN"`, or empty
- **SpecialTaxation**: `"T"` or empty string
- **VATPointDate**: Date format `YYYY-MM-DD`, nullable (if different from InvoiceDate)
- **RegistrationAccountDate**: Date format `YYYY-MM-DD`, nullable (only for Purchase invoices)

### Supplier/Customer Info
- **SupplierID/CustomerID**: Max 70 characters, optional
- **VATRegistrationNumber**: Max 35 characters, min 1, required (use `"ND"` if unknown)
- **RegistrationNumber**: Max 35 characters, optional (use `"ND"` if unknown and VAT is "ND")
- **Country**: Exactly 2 characters (ISO 3166-1 alpha 2), nullable (required if VAT is "ND" or non-EU)
- **Name**: Max 256 characters (use `"ND"` if unknown)

### Document Totals
- **TaxableValue**: Decimal, 18 total digits, 2 fraction digits (amount without VAT)
- **TaxCode**: Pattern `PVM[0-9]*`, 4-6 characters (e.g., "PVM1", "PVM25"), nullable
- **TaxPercentage**: Decimal, 5 total digits, 2 fraction digits, nullable (VAT rate %, use "0" for 0%)
- **Amount**: Decimal, 18 total digits, 2 fraction digits, nullable (VAT amount)

## Special Rules

1. **Missing Data**: Use `"ND"` (no data) for required fields when data is unknown
2. **VAT Number Format**: Must include country prefix (e.g., "LT123456789")
3. **Date Formats**: All dates must be `YYYY-MM-DD` format
4. **Monetary Values**: Always 2 decimal places, max 18 digits total
5. **Tax Code**: Default to "PVM1" if not determined, use "PVM25" for "96 straipsnis" or "atvirkštinis PVM"
6. **Invoice Type**: Default to "SF" (or empty) for standard invoices

## Notes

- Purchase invoices: Use `PurchaseInvoices` section
- Sales invoices: Use `SalesInvoices` section
- MasterFiles section is optional (can include Customers/Suppliers master data)
- References section is optional (for credit/debit invoices)
- SettlementsAndPayments section is optional (for monetary accounting system)










