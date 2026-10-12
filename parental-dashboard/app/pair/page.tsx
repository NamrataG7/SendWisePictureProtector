'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { createClient } from '@/utils/supabase/client';

type AuthState = 'loading' | 'signed-in' | 'signed-out';

export default function PairPage() {
  const router = useRouter();
  const [authState, setAuthState] = useState<AuthState>('loading');

  const [code, setCode] = useState('');
  const [childName, setChildName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const supabase = createClient();
      const { data } = await supabase.auth.getUser();
      if (cancelled) return;
      if (!data.user) {
        setAuthState('signed-out');
        router.push(`/login?callbackUrl=${encodeURIComponent('/pair')}`);
      } else {
        setAuthState('signed-in');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [router]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    const cleanCode = code.trim();
    if (!/^\d{6}$/.test(cleanCode)) {
      setError('Pairing code must be 6 digits.');
      return;
    }

    setLoading(true);
    try {
      // parent_id is derived server-side from the Supabase session — do NOT
      // send it in the body. /api/pairing/redeem rejects any body key it
      // doesn't expect.
      const res = await fetch('/api/pairing/redeem', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code: cleanCode,
          ...(childName.trim() ? { child_name: childName.trim() } : {}),
        }),
      });

      if (res.status === 401) {
        router.push(`/login?callbackUrl=${encodeURIComponent('/pair')}`);
        return;
      }

      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        setError(body?.error || 'Could not redeem code. It may be invalid or expired.');
        setLoading(false);
        return;
      }

      setSuccess(true);
      setTimeout(() => {
        router.push('/');
        router.refresh();
      }, 1500);
    } catch {
      setError('Network error. Please try again.');
      setLoading(false);
    }
  }

  if (authState !== 'signed-in') {
    return (
      <main className="min-h-screen flex items-center justify-center bg-gray-50">
        <p className="text-sm text-gray-500">Loading…</p>
      </main>
    );
  }

  return (
    <main className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-md bg-white rounded-2xl shadow-sm border border-gray-100 p-8">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-gray-900">Link a child device</h1>
          <p className="text-sm text-gray-500 mt-1">
            Enter the 6-digit code shown in the SendWise keyboard on your child&apos;s device.
          </p>
        </div>

        {success ? (
          <div className="text-sm text-green-700 bg-green-50 border border-green-100 rounded-lg px-4 py-3">
            Child device linked. Redirecting to your dashboard…
          </div>
        ) : (
          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <label htmlFor="code" className="block text-sm font-medium text-gray-700 mb-1">
                Pairing code
              </label>
              <input
                id="code"
                type="text"
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                required
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-lg tracking-widest text-center text-gray-900 placeholder-gray-300 focus:outline-none focus:ring-2 focus:ring-[#6C3FE1] focus:border-transparent"
                placeholder="123456"
              />
            </div>

            <div>
              <label htmlFor="childName" className="block text-sm font-medium text-gray-700 mb-1">
                Child name <span className="text-gray-400 font-normal">(optional)</span>
              </label>
              <input
                id="childName"
                type="text"
                value={childName}
                onChange={(e) => setChildName(e.target.value)}
                className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-[#6C3FE1] focus:border-transparent"
                placeholder="e.g. Alex"
              />
            </div>

            {error && (
              <div className="text-sm text-red-600 bg-red-50 border border-red-100 rounded-lg px-3 py-2">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-lg bg-[#6C3FE1] hover:bg-[#5b34c7] disabled:bg-[#a58ce8] text-white text-sm font-semibold py-2.5 transition-colors"
            >
              {loading ? 'Linking…' : 'Link device'}
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
