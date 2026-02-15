# i.SAF XML Generation – Verification Report

**Date:** February 15, 2026  
**Reference:** Official i.SAF XSD 1.2 specification (VMI, 2016-09-16)  
**Specification:** i.SAF duomenų rinkmenos XML struktūros aprašo aprašymas v1.2.1 (2016-10-12)

---

## Summary

The app’s i.SAF XML export logic has been checked against the official i.SAF 1.2 XSD and VMI documentation. The implementation matches the specification and is valid.

---

## 1. Official Requirements (from VMI XSD 1.2)

### 1.1 Schema Versions

- **Current XSD:** i.SAF 1.2 (`isaf_1.2.xsd`, 2016-09-16)
- **FileVersion:** `iSAF1.2`
- **Namespace:** `http://www.vmi.lt/cms/imas/isaf`

### 1.2 DocumentTotal Structure (critical difference)

The XSD defines different structures for Purchase and Sales invoices:

| Invoice Type | DocumentTotal Type | VATPointDate2 |
|--------------|--------------------|---------------|
| **Purchase** | PurchaseDocumentTotal | Not allowed |
| **Sales**    | SalesDocumentTotal   | Required (nillable) |

From the XSD 1.2 change log:
- `SalesDocumentTotal->VATPointDate2` – nillable
- `PurchaseDocumentTotal` – no VATPointDate2

---

## 2. Implementation Verification

### 2.1 Header Section

| Element | Requirement | Implementation | Status |
|---------|-------------|----------------|--------|
| FileVersion | `iSAF1.2` | `FILE_VERSION = "iSAF1.2"` | OK |
| FileDateCreated | `xs:dateTime` with timezone | `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC) | OK |
| DataType | F / S / P | From `determineDataType()` | OK |
| SoftwareCompanyName | Max 256 chars | Fixed value | OK |
| SoftwareName | Max 256 chars | Fixed value | OK |
| SoftwareVersion | Max 24 chars | `BuildConfig.VERSION_NAME` | OK |
| RegistrationNumber | Unsigned long | From `ownCompany.company_number` | OK |
| NumberOfParts | Default 1 | `"1"` | OK |
| PartNumber | `[A-Z0-9_]*`, 1–20 chars | `"1"` | OK |
| SelectionStartDate | `YYYY-MM-DD`, min 2016-07-01 | From month range | OK |
| SelectionEndDate | `YYYY-MM-DD` | From month range | OK |

### 2.2 Purchase Invoice Structure

| Element | Requirement | Implementation | Status |
|---------|-------------|----------------|--------|
| InvoiceNo | minLength 1 | Spaces/dashes removed, fallback `"ND"` | OK |
| SupplierInfo | VATRegistrationNumber, RegistrationNumber?, Country, Name | Implemented | OK |
| InvoiceDate | `xs:date` | `YYYY-MM-DD`, fallback to current date | OK |
| InvoiceType | SF, DS, KS, etc. | `"SF"` | OK |
| SpecialTaxation | `""` or `"T"` | `""` | OK |
| References | Empty | Empty element | OK |
| VATPointDate | nillable | Invoice date | OK |
| RegistrationAccountDate | nillable | `xsi:nil="true"` | OK |
| DocumentTotals | PurchaseDocumentTotal | No VATPointDate2 | OK |

### 2.3 Sales Invoice Structure

| Element | Requirement | Implementation | Status |
|---------|-------------|----------------|--------|
| InvoiceNo | minLength 1 | Spaces/dashes removed, fallback `"ND"` | OK |
| CustomerInfo | VATRegistrationNumber, RegistrationNumber?, Country, Name | Implemented | OK |
| InvoiceDate | ISAFDateType3 (min 2016-07-01) | `YYYY-MM-DD` | OK |
| InvoiceType | SF, DS, KS, etc. | `"SF"` | OK |
| SpecialTaxation | `""` or `"T"` | `""` | OK |
| References | Empty | Empty element | OK |
| VATPointDate | nillable | Invoice date | OK |
| DocumentTotals | SalesDocumentTotal | **VATPointDate2 included** | OK |

### 2.4 DocumentTotal (per invoice type)

**Purchase (PurchaseDocumentTotal):**
- TaxableValue
- TaxCode (PVM[0-9]*, 4–6 chars)
- TaxPercentage
- Amount
- No VATPointDate2

**Sales (SalesDocumentTotal):**
- TaxableValue
- TaxCode (PVM[0-9]*, 4–6 chars)
- TaxPercentage
- Amount
- **VATPointDate2** (required, uses invoice date)

### 2.5 SupplierInfo / CustomerInfo

| Element | Requirement | Implementation | Status |
|--------|-------------|----------------|--------|
| VATRegistrationNumber | Required, `"ND"` if unknown | Normalized, `"ND"` fallback | OK |
| RegistrationNumber | When VAT is `"ND"` | Only when VAT is `"ND"` | OK |
| Country | 2 chars (ISO 3166-1 alpha 2), nillable | From VAT prefix or `xsi:nil` | OK |
| Name | Required, `"ND"` if unknown | `"ND"` fallback | OK |
| Element order | Country before Name | Enforced | OK |

### 2.6 MasterFiles

- Optional (`minOccurs="0"`)
- Customers from sales invoices
- Suppliers from purchase invoices
- Sequence: CustomerID, VATRegistrationNumber, RegistrationNumber, Country, Name

---

## 3. Validation Errors Addressed

| Error | Cause | Fix |
|-------|-------|-----|
| `VATPointDate2 expected` (line 1514, Sales) | SalesDocumentTotal must include VATPointDate2 | Add VATPointDate2 for Sales invoices |
| `VATPointDate2 - No child element expected` (line 215, Purchase) | PurchaseDocumentTotal must not include VATPointDate2 | Add VATPointDate2 only when `!isPurchase` |

---

## 4. Potential Improvements

1. **InvoiceType** – Support DS, KS, VS, VD, VK, AN when data is available.
2. **RegistrationAccountDate** – Use real accounting date when known.
3. **References** – Use for credit/debit invoice references.
4. **SpecialTaxation** – Set `"T"` when applicable.
5. **Multiple DocumentTotal** – Support several tax codes per invoice.
6. **TaxCode mapping** – Align with PVM klasifikatorius (e.g. PVM1–PVM4).

---

## 5. References

- VMI i.SAF page: https://www.vmi.lt/evmi/i.saf2
- XSD 1.2: isaf_1.2.xsd (2016-09-16)
- Specification: i.SAF duomenų rinkmenos XML struktūros aprašo aprašymas v1.2.1 (2016-10-12)
- Filing: Mandatory monthly by the 20th of the following month

---

## 6. Conclusion

The i.SAF XML export logic matches the official i.SAF 1.2 XSD and VMI requirements. The main rule is:

- **Sales invoices:** include `VATPointDate2` in each `DocumentTotal`.
- **Purchase invoices:** do not include `VATPointDate2` in `DocumentTotal`.

The current implementation follows this rule and is valid for VMI submission.
