-- Migration: Add subscription tracking to users table
-- This enables subscription management and cross-device sync

-- Add subscription columns to users table
ALTER TABLE public.users
ADD COLUMN IF NOT EXISTS subscription_plan TEXT DEFAULT 'free',
ADD COLUMN IF NOT EXISTS subscription_status TEXT DEFAULT 'active',
ADD COLUMN IF NOT EXISTS subscription_start_date TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS subscription_end_date TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS purchase_token TEXT,
ADD COLUMN IF NOT EXISTS pages_used INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS usage_reset_date TIMESTAMPTZ;

-- Create indexes for subscription queries
CREATE INDEX IF NOT EXISTS idx_users_subscription_plan ON public.users(subscription_plan);
CREATE INDEX IF NOT EXISTS idx_users_subscription_status ON public.users(subscription_status);

-- Add check constraint for subscription_plan
ALTER TABLE public.users
DROP CONSTRAINT IF EXISTS check_subscription_plan;

ALTER TABLE public.users
ADD CONSTRAINT check_subscription_plan 
CHECK (subscription_plan IN ('free', 'basic_monthly', 'pro_monthly'));

-- Add check constraint for subscription_status
ALTER TABLE public.users
DROP CONSTRAINT IF EXISTS check_subscription_status;

ALTER TABLE public.users
ADD CONSTRAINT check_subscription_status 
CHECK (subscription_status IN ('active', 'expired', 'canceled', 'pending'));

-- RLS policies already exist for users table, so subscription data is automatically protected

