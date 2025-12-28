-- Migration: Add invoice_type, vat_rate, and tax_code columns to invoices table

-- Add invoice_type column (P = Purchase/Received, S = Sales/Issued)
ALTER TABLE public.invoices 
ADD COLUMN IF NOT EXISTS invoice_type TEXT DEFAULT 'P' CHECK (invoice_type IN ('P', 'S'));

-- Add vat_rate column (numeric, e.g., 21.0, 9.0, 5.0, 0.0)
ALTER TABLE public.invoices 
ADD COLUMN IF NOT EXISTS vat_rate NUMERIC(5,2);

-- Add tax_code column (e.g., PVM1, PVM25)
ALTER TABLE public.invoices 
ADD COLUMN IF NOT EXISTS tax_code TEXT DEFAULT 'PVM1';

-- Create index for faster filtering by invoice type
CREATE INDEX IF NOT EXISTS idx_invoices_invoice_type 
ON public.invoices(invoice_type);






















