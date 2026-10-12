import { createBrowserClient } from '@supabase/ssr';

/**
 * Supabase client for use in Client Components ('use client').
 * Reads the public env vars baked into the browser bundle.
 */
export function createClient() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key) {
    throw new Error(
      '[supabase/client] NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY must be set',
    );
  }
  return createBrowserClient(url, key);
}
