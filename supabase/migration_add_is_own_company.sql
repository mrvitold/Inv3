-- Migration: Add is_own_company column to companies table
-- Run this in your Supabase SQL Editor if the column doesn't exist

-- Add the column if it doesn't exist
ALTER TABLE public.companies
ADD COLUMN IF NOT EXISTS is_own_company BOOLEAN DEFAULT FALSE;

-- Create index for faster filtering of own companies
CREATE INDEX IF NOT EXISTS idx_companies_is_own_company
ON public.companies(is_own_company);

