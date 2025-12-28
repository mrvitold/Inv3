-- Migration: Add user_id columns, indexes, and RLS policies for multi-user support
-- This enables personalized accounts while maintaining shared company database for verification

-- Add user_id column to invoices table
ALTER TABLE public.invoices
ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE;

-- Add user_id column to companies table (nullable - for shared verification)
ALTER TABLE public.companies
ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE;

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_invoices_user_id ON public.invoices(user_id);
CREATE INDEX IF NOT EXISTS idx_companies_user_id ON public.companies(user_id);
CREATE INDEX IF NOT EXISTS idx_companies_user_id_is_own ON public.companies(user_id, is_own_company);

-- Enable Row Level Security on both tables
ALTER TABLE public.invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.companies ENABLE ROW LEVEL SECURITY;

-- RLS Policies for invoices table
-- Users can only SELECT their own invoices
DROP POLICY IF EXISTS "Users can view own invoices" ON public.invoices;
CREATE POLICY "Users can view own invoices" ON public.invoices
  FOR SELECT USING (auth.uid() = user_id);

-- Users can only INSERT their own invoices
DROP POLICY IF EXISTS "Users can insert own invoices" ON public.invoices;
CREATE POLICY "Users can insert own invoices" ON public.invoices
  FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Users can only UPDATE their own invoices
DROP POLICY IF EXISTS "Users can update own invoices" ON public.invoices;
CREATE POLICY "Users can update own invoices" ON public.invoices
  FOR UPDATE USING (auth.uid() = user_id);

-- Users can only DELETE their own invoices
DROP POLICY IF EXISTS "Users can delete own invoices" ON public.invoices;
CREATE POLICY "Users can delete own invoices" ON public.invoices
  FOR DELETE USING (auth.uid() = user_id);

-- RLS Policies for companies table
-- Users can SELECT their own companies
DROP POLICY IF EXISTS "Users can view own companies" ON public.companies;
CREATE POLICY "Users can view own companies" ON public.companies
  FOR SELECT USING (auth.uid() = user_id);

-- Users can SELECT all companies for verification (read-only, shared database)
-- This allows invoice scanning to use all companies from all users
DROP POLICY IF EXISTS "Users can view all companies for verification" ON public.companies;
CREATE POLICY "Users can view all companies for verification" ON public.companies
  FOR SELECT USING (true); -- All authenticated users can read all companies

-- Users can INSERT their own companies
DROP POLICY IF EXISTS "Users can insert own companies" ON public.companies;
CREATE POLICY "Users can insert own companies" ON public.companies
  FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Users can UPDATE their own companies
DROP POLICY IF EXISTS "Users can update own companies" ON public.companies;
CREATE POLICY "Users can update own companies" ON public.companies
  FOR UPDATE USING (auth.uid() = user_id);

-- Users can DELETE their own companies
DROP POLICY IF EXISTS "Users can delete own companies" ON public.companies;
CREATE POLICY "Users can delete own companies" ON public.companies
  FOR DELETE USING (auth.uid() = user_id);

-- Note: The shared verification policy allows all users to read all companies,
-- but they can only modify (INSERT/UPDATE/DELETE) their own companies.
-- This enables invoice scanning to benefit from collective company data
-- while maintaining data ownership and privacy.

