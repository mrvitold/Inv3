# i.SAF InvoiceType Values

## Allowed Values

According to the i.SAF 1.2 XSD schema (`ISAFshorttext2Type`), the `InvoiceType` element can have the following values:

| Value | Description (Lithuanian) | Description (English) | Usage |
|-------|-------------------------|----------------------|-------|
| **SF** | PVM sąskaita faktūra | VAT invoice | Standard VAT invoice (default if empty) |
| **DS** | Debetinė PVM sąskaita faktūra | Debit VAT invoice | Debit invoice (correction/adjustment) |
| **KS** | Kreditinė PVM sąskaita faktūra | Credit VAT invoice | Credit invoice (correction/adjustment) |
| **VS** | Viena (advokatų/notarų) PVM sąskaita faktūra | One summary VAT invoice (attorneys/notaries) | Summary invoice for attorneys/notaries |
| **VD** | Viena (advokatų/notarų) PVM sąskaita faktūra debetinė | One summary debit VAT invoice (attorneys/notaries) | Summary debit invoice for attorneys/notaries |
| **VK** | Viena (advokatų/notarų) PVM sąskaita faktūra kreditinė | One summary credit VAT invoice (attorneys/notaries) | Summary credit invoice for attorneys/notaries |
| **AN** | Anuliuota | Cancelled | Cancelled invoice |
| **(empty)** | - | - | Defaults to SF (VAT invoice) |

## Notes

- If the element value is not completed (empty), it is considered to refer to a VAT invoice (SF type)
- The value is case-sensitive and must match exactly
- These values are different from the `invoice_type` field in our database, which uses "P" (Purchase) or "S" (Sales)

## Current Implementation

Currently, we set `InvoiceType` to empty string (""), which defaults to SF (standard VAT invoice). This is correct for most cases.

## Future Enhancement

To properly determine InvoiceType, we would need:
- A field in the invoice data to indicate if it's a debit/credit/cancelled invoice
- Logic to detect invoice type from invoice content or user input
- For now, defaulting to SF (empty) is acceptable for standard invoices

