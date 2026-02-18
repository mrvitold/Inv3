-- Migration: Add accounting_monthly to subscription plan constraint
-- Enables the new Accounting tier (401-3000 invoices, 50 companies)

-- Drop existing constraint
ALTER TABLE public.users
DROP CONSTRAINT IF EXISTS check_subscription_plan;

-- Add updated constraint with accounting_monthly
ALTER TABLE public.users
ADD CONSTRAINT check_subscription_plan 
CHECK (subscription_plan IN ('free', 'basic_monthly', 'pro_monthly', 'accounting_monthly'));
