import { createServerClient, type CookieOptions } from '@supabase/ssr';
import type { cookies } from 'next/headers';

/**
 * The awaited return type of Next.js's `cookies()` from `next/headers`.
 * Works across Next 14 (sync store) and Next 15 (async store).
 */
type CookieStore = Awaited<ReturnType<typeof cookies>>;

/**
 * Supabase client for Next.js Server Components / Route Handlers.
 *
 * Pass in the `cookies()` from `next/headers`. Always `await cookies()`
 * before passing in — this keeps the call site forward-compatible with
 * Next 15's async cookies API.
 *
 * Usage:
 *   import { cookies } from 'next/headers'
 *   const supabase = createClient(await cookies())
 *   const { data: { user } } = await supabase.auth.getUser()
 */
export function createClient(cookieStore: CookieStore) {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key) {
    throw new Error(
      '[supabase/server] NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY must be set',
    );
  }

  return createServerClient(url, key, {
    cookies: {
      get(name: string) {
        return cookieStore.get(name)?.value;
      },
      // In pure Server Components the cookie store is read-only; writes
      // will throw. That's fine — the middleware handles session refresh
      // and cookie rotation. We swallow the error so RSC reads don't crash.
      set(name: string, value: string, options: CookieOptions) {
        try {
          cookieStore.set({ name, value, ...options });
        } catch {
          // no-op in read-only contexts
        }
      },
      remove(name: string, options: CookieOptions) {
        try {
          cookieStore.set({ name, value: '', ...options });
        } catch {
          // no-op in read-only contexts
        }
      },
    },
  });
}
