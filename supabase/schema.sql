-- Supabase schema for Inv3

create table if not exists public.invoices (
  id uuid primary key default gen_random_uuid(),
  invoice_id text,
  date date,
  company_name text,
  amount_without_vat_eur numeric(12,2),
  vat_amount_eur numeric(12,2),
  vat_number text,
  company_number text,
  invoice_type text default 'P' check (invoice_type in ('P', 'S')),
  vat_rate numeric(5,2),
  tax_code text default 'PVM1',
  created_at timestamptz default now()
);

create table if not exists public.companies (
  id uuid primary key default gen_random_uuid(),
  company_number text unique,
  company_name text,
  vat_number text,
  updated_at timestamptz default now()
);

-- Add is_own_company column if it doesn't exist (for existing databases)
ALTER TABLE public.companies
ADD COLUMN IF NOT EXISTS is_own_company BOOLEAN DEFAULT FALSE;

-- Create index for faster filtering of own companies
create index if not exists idx_companies_is_own_company 
on public.companies(is_own_company);

-- Optional: per-company templates
create table if not exists public.company_templates (
  company_id uuid references public.companies(id) on delete cascade,
  layout_signature text,
  regions jsonb,
  keywords_overrides jsonb,
  updated_at timestamptz default now(),
  primary key (company_id)
);

