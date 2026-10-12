'use client';

import { useState } from 'react';
import { Incident } from '@/lib/types';

interface IncidentCardProps {
  incident: Incident;
  userIdHash: string;
  onReviewed?: (incidentId: string) => void;
}

const severityConfig = {
  urgent: {
    color: 'bg-red-100 border-red-500 text-red-900',
    icon: '🚨',
    badge: 'bg-red-600 text-white'
  },
  critical: {
    color: 'bg-orange-100 border-orange-500 text-orange-900',
    icon: '🚨',
    badge: 'bg-orange-600 text-white'
  },
  high: {
    color: 'bg-yellow-100 border-yellow-500 text-yellow-900',
    icon: '⚠️',
    badge: 'bg-yellow-600 text-white'
  },
  medium: {
    color: 'bg-yellow-50 border-yellow-400 text-yellow-800',
    icon: '⚠️',
    badge: 'bg-yellow-500 text-white'
  },
  low: {
    color: 'bg-green-50 border-green-400 text-green-800',
    icon: 'ℹ️',
    badge: 'bg-green-500 text-white'
  },
  none: {
    color: 'bg-gray-50 border-gray-300 text-gray-700',
    icon: '',
    badge: 'bg-gray-500 text-white'
  }
};

const categoryLabels = {
  harassment: 'Harassment',
  threats: 'Threats',
  hate_speech: 'Hate Speech',
  sexual_content: 'Sexual Content',
  self_harm: 'Self-Harm Risk',
  sensitive_image: 'Sensitive Image'
};

const platformLabels = {
  instagram: 'Instagram',
  discord: 'Discord',
  whatsapp: 'WhatsApp',
  facebook: 'Facebook',
  twitter: 'Twitter/X',
  tiktok: 'TikTok',
  snapchat: 'Snapchat',
  reddit: 'Reddit',
  other: 'Other'
};

const actionLabels = {
  blocked: 'Blocked by keyboard',
  edited: 'User edited message',
  sent_anyway: 'Sent anyway (Override)',
  cancelled: 'User cancelled'
};

export default function IncidentCard({ incident, userIdHash, onReviewed }: IncidentCardProps) {
  const [busy, setBusy] = useState(false);

  const handleMarkReviewed = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const res = await fetch(
        `/api/violations/${userIdHash}/${encodeURIComponent(incident.id)}`,
        { method: 'DELETE' },
      );
      if (!res.ok) {
        console.error('Mark reviewed failed:', res.status);
        setBusy(false);
        return;
      }
      onReviewed?.(incident.id);
    } catch (err) {
      console.error('Mark reviewed error:', err);
      setBusy(false);
    }
  };
  const config = severityConfig[incident.severity];
  const timestamp = new Date(incident.timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true
  });

  return (
    <div className={`border-l-4 ${config.color} rounded-lg p-4 mb-4 shadow-sm`}>
      {/* Header */}
      <div className="flex items-start justify-between mb-2">
        <div className="flex items-center gap-2">
          <span className="text-2xl">{config.icon}</span>
          <div>
            <h3 className="font-bold text-lg">
              {categoryLabels[incident.category]}
            </h3>
            <p className="text-sm opacity-75">
              {timestamp} · {platformLabels[incident.platform]}
            </p>
          </div>
        </div>
        <span className={`${config.badge} px-3 py-1 rounded-full text-xs font-bold uppercase`}>
          {incident.severity}
        </span>
      </div>

      {/* Privacy guarantee (SendWise paper §Privacy by Design):
          Message content is analyzed on-device only. This UI intentionally shows NO message text. */}
      <div className="mb-3">
        <div className="flex items-center gap-2 text-sm text-gray-500 italic">
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
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            <path d="m9 12 2 2 4-4" />
          </svg>
          <span>Message content is private and never leaves the child&apos;s device (SendWise privacy policy)</span>
        </div>
      </div>

      {/* Action Taken */}
      <div className="mb-3">
        <p className="text-sm">
          <span className="font-semibold">Action:</span> {actionLabels[incident.action]}
        </p>
      </div>

      {/* Recommendation */}
      <div className="mb-3">
        <p className="font-semibold text-sm mb-1">Recommendation:</p>
        <p className="text-sm font-medium">{incident.recommendation}</p>
      </div>

      {/* Resources */}
      {incident.resources && incident.resources.length > 0 && (
        <div className="mb-3">
          <p className="font-semibold text-sm mb-1">Resources:</p>
          <ul className="text-sm list-disc list-inside space-y-1">
            {incident.resources.map((resource, idx) => (
              <li key={idx}>{resource}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Action Buttons */}
      <div className="flex gap-2 mt-4">
        <button
          type="button"
          onClick={handleMarkReviewed}
          disabled={busy}
          className={
            (incident.severity === 'urgent' || incident.severity === 'critical'
              ? 'bg-red-600 hover:bg-red-700'
              : 'bg-gray-600 hover:bg-gray-700') +
            ' px-4 py-2 text-white rounded-lg text-sm font-medium transition disabled:opacity-60'
          }
        >
          {busy ? 'Removing…' : 'Mark Reviewed'}
        </button>
      </div>
    </div>
  );
}
