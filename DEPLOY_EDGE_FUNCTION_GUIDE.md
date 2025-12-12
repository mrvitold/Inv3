# Deploy Edge Function - Easy Guide

## Option 1: Deploy via Supabase Dashboard (Easiest - No CLI Required)

### Step 1: Go to Supabase Dashboard
1. Open https://supabase.com/dashboard
2. Select your project

### Step 2: Navigate to Edge Functions
1. Click on **"Edge Functions"** in the left sidebar
2. Click **"Create a new function"** or **"New Function"**

### Step 3: Create the Function
1. **Function Name:** `delete_user_account`
2. **Copy the code** from `supabase/functions/delete_user_account/index.ts`
3. **Paste it** into the editor
4. Click **"Deploy"** or **"Save"**

### Step 4: Verify
- The function should appear in your Edge Functions list
- Status should show as "Active" or "Deployed"

## Option 2: Install CLI and Deploy (More Control)

### Step 1: Install Node.js
1. Download Node.js from: https://nodejs.org/
2. Install it (includes npm)
3. Restart your terminal/command prompt

### Step 2: Install Supabase CLI
```bash
npm install -g supabase
```

### Step 3: Login to Supabase
```bash
supabase login
```
This will open a browser window for authentication.

### Step 4: Link Your Project
```bash
supabase link --project-ref YOUR_PROJECT_REF
```
Find your project ref in: Supabase Dashboard → Settings → General → Reference ID

### Step 5: Deploy the Function
```bash
supabase functions deploy delete_user_account
```

## Verify Deployment

After deploying (either method), test it:

1. Go to Supabase Dashboard → Edge Functions
2. Click on `delete_user_account`
3. You should see the function code and logs

## Test in Your App

1. Build and run your app
2. Go to Settings → Delete My Account
3. Confirm deletion
4. Check Supabase Dashboard → Authentication → Users to verify the account was deleted

## Troubleshooting

- **Function not found**: Make sure the function name is exactly `delete_user_account`
- **401 Unauthorized**: User needs to be signed in
- **500 Error**: Check function logs in Supabase Dashboard

