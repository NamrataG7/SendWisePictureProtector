'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';

type Props = {
  childHashes: string[];
};

/**
 * UnlinkDeviceButton — shows an "Unlink" pill in the dashboard header
 * that lets the parent detach the currently linked child device(s).
 *
 * Behavior:
 * - Confirms with a native window.confirm before unlinking
 * - Calls DELETE /api/parent/children/[user_id_hash] for each linked hash
 * - Refreshes the server component so the UI reverts to the State-A empty state
 * - No-op if there are no linked children
 */
export default function UnlinkDeviceButton({ childHashes }: Props) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (childHashes.length === 0) return null;

  const label =
    childHashes.length === 1
      ? 'Unlink device'
      : `Unlink ${childHashes.length} devices`;

  const handleUnlink = async () => {
    if (busy) return;
    const confirmed = window.confirm(
      childHashes.length === 1
        ? 'Unlink this child device? The dashboard will stop receiving updates and its history will be deleted. You can re-pair anytime with a new code.'
        : `Unlink all ${childHashes.length} linked child devices? The dashboard will stop receiving updates from all of them and their histories will be deleted.`,
    );
    if (!confirmed) return;

    setBusy(true);
    setError(null);
    try {
      const results = await Promise.allSettled(
        childHashes.map((hash) =>
          fetch(`/api/parent/children/${hash}`, { method: 'DELETE' }),
        ),
      );
      const anyFailed = results.some(
        (r) => r.status === 'rejected' || (r.status === 'fulfilled' && !r.value.ok),
      );
      if (anyFailed) {
        setError('Some devices could not be unlinked. Please try again.');
      }
      router.refresh();
    } catch {
      setError('Network error. Please try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={handleUnlink}
        disabled={busy}
        title={label}
        className="px-4 py-2 bg-red-50 text-red-700 border border-red-200 rounded-lg font-medium hover:bg-red-100 disabled:opacity-60 transition inline-flex items-center gap-2"
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
          <line x1="12" y1="2" x2="12" y2="12" />
        </svg>
        {busy ? 'Unlinking…' : label}
      </button>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
