-- Migration: Add own_company_id to invoices table
-- Invoices are associated with the own company selected when scanned and approved.
-- Enables filtering exports by company.

ALTER TABLE public.invoices
ADD COLUMN IF NOT EXISTS own_company_id uuid REFERENCES public.companies(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_own_company_id ON public.invoices(own_company_id);
