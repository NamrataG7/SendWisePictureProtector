'use client';

import { useState } from 'react';
import { createClient } from '@/utils/supabase/client';

export default function SignupForm() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [needsConfirm, setNeedsConfirm] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const supabase = createClient();
      const { data, error: signUpError } = await supabase.auth.signUp({
        email,
        password,
        options: {
          emailRedirectTo: `${window.location.origin}/auth/callback`,
        },
      });
      if (signUpError) {
        setError(signUpError.message);
        setLoading(false);
        return;
      }
      // If email confirmation is required, `session` will be null.
      if (!data.session) {
        setNeedsConfirm(true);
        setLoading(false);
        return;
      }
      // Otherwise the user is signed in immediately.
      window.location.assign('/');
    } catch {
      setError('Something went wrong. Please try again.');
      setLoading(false);
    }
  }

  if (needsConfirm) {
    return (
      <div className="text-sm text-green-700 bg-green-50 border border-green-100 rounded-lg px-4 py-3">
        Check your inbox for a confirmation email. Once confirmed, you can{' '}
        <a href="/login" className="underline font-medium">sign in</a>.
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div>
        <label htmlFor="email" className="block text-sm font-medium text-gray-700 mb-1">
          Email
        </label>
        <input
          id="email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-[#6C3FE1] focus:border-transparent"
          placeholder="parent@example.com"
        />
      </div>

      <div>
        <label htmlFor="password" className="block text-sm font-medium text-gray-700 mb-1">
          Password
        </label>
        <input
          id="password"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-[#6C3FE1] focus:border-transparent"
          placeholder="At least 8 characters"
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
        {loading ? 'Creating account…' : 'Create account'}
      </button>
    </form>
  );
}
