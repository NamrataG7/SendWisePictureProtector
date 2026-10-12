'use client';

import { useState, useMemo, useRef } from 'react';
import Link from 'next/link';
import IncidentCard from '@/components/IncidentCard';
import StatsOverview from '@/components/StatsOverview';
import CategoryFilter from '@/components/CategoryFilter';
import SignOutButton from '@/components/SignOutButton';
import UnlinkDeviceButton from '@/components/UnlinkDeviceButton';
import { IncidentCategory, Incident, DashboardStats } from '@/lib/types';

type Props = {
  parentLabel: string;
  childCount: number;
  childHashes: string[];
  incidents: Incident[];
  stats: DashboardStats;
};

export default function DashboardClient({
  parentLabel,
  childCount,
  childHashes,
  incidents: initialIncidents,
  stats,
}: Props) {
  // Local state so 'Mark Reviewed' can update instantly without waiting
  // for a router.refresh() round-trip. Server list is authoritative on
  // next load, and the reviewed flag is persisted to Redis via the
  // DELETE endpoint (which soft-flags, not deletes — historical data
  // is preserved for Insights and CSV export).
  const [incidents, setIncidents] = useState<Incident[]>(initialIncidents);

  const handleReviewed = (incidentId: string) => {
    // Soft-flag locally so the card disappears from the home feed but
    // stays in filteredIncidents (used by CSV export) and stats.
    setIncidents((cur) =>
      cur.map((i) => (i.id === incidentId ? { ...i, reviewed: true } : i)),
    );
  };

  // "Review Now" scroll-to-first-critical wiring
  const criticalSectionRef = useRef<HTMLDivElement | null>(null);
  const [flashCritical, setFlashCritical] = useState(false);

  const handleReviewNow = () => {
    const el = criticalSectionRef.current;
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    setFlashCritical(true);
    window.setTimeout(() => setFlashCritical(false), 1400);
  };

  const [selectedCategories, setSelectedCategories] = useState<IncidentCategory[]>([
    'harassment',
    'threats',
    'hate_speech',
    'sexual_content',
    'self_harm',
    'sensitive_image',
  ]);

  const handleCategoryToggle = (category: IncidentCategory) => {
    setSelectedCategories(prev =>
      prev.includes(category)
        ? prev.filter(c => c !== category)
        : [...prev, category]
    );
  };

  // filteredIncidents keeps EVERYTHING (including reviewed) — used by CSV export.
  const filteredIncidents = useMemo(
    () => incidents.filter(inc => selectedCategories.includes(inc.category)),
    [incidents, selectedCategories],
  );

  // visibleIncidents drops reviewed — used by home feed sections + count.
  const visibleIncidents = useMemo(
    () => filteredIncidents.filter(inc => !inc.reviewed),
    [filteredIncidents],
  );

  const handleExportReport = () => {
    // Privacy guarantee (SendWise paper §Privacy by Design):
    // Exported reports contain metadata only. Message content is analyzed on-device
    // and never leaves the child's device, so it is not — and cannot be — exported.
    // Reviewed incidents ARE included so the audit trail stays intact.
    const headers = ['Timestamp', 'Platform', 'Category', 'Severity', 'Action', 'Reviewed', 'Recommendation'];
    const rows = filteredIncidents.map(inc => [
      new Date(inc.timestamp).toLocaleString(),
      inc.platform,
      inc.category,
      inc.severity,
      inc.action,
      inc.reviewed ? 'yes' : 'no',
      inc.recommendation,
    ]);

    const csvContent = [
      headers.join(','),
      ...rows.map(row => row.map(cell => `"${cell}"`).join(',')),
    ].join('\n');

    const blob = new Blob([csvContent], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `sendwise-report-${new Date().toISOString().split('T')[0]}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const criticalIncidents = visibleIncidents.filter(
    inc => inc.severity === 'urgent' || inc.severity === 'critical' || inc.severity === 'high',
  );
  const otherIncidents = visibleIncidents.filter(
    inc => !(inc.severity === 'urgent' || inc.severity === 'critical' || inc.severity === 'high'),
  );

  const deviceLabel =
    childCount === 1 ? '1 device linked' : `${childCount} devices linked`;

  // State B: paired but zero incidents ever recorded.
  const showEmptyIncidentsMessage =
    incidents.length === 0 && stats.totalIncidents === 0 && childCount > 0;

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100">
      {/* Header */}
      <header className="bg-white shadow-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-3xl font-bold text-gray-900">SendWise</h1>
              <p className="text-sm text-gray-600">Parental Dashboard — {parentLabel}</p>
            </div>
            <div className="flex items-center gap-4">
              <div className="text-right">
                <p className="text-sm text-gray-600">Linked</p>
                <p className="font-semibold">{deviceLabel}</p>
              </div>
              <Link
                href="/insights"
                className="px-4 py-2 bg-purple-600 text-white rounded-lg font-medium hover:bg-purple-700 transition"
              >
                View Insights →
              </Link>
              <button
                onClick={handleExportReport}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition"
              >
                📥 Export Report
              </button>
              <UnlinkDeviceButton childHashes={childHashes} />
              <SignOutButton />
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {/* Stats Overview */}
        <StatsOverview stats={stats} />

        {/* Data Limitation Disclaimer */}
        <div className="bg-yellow-50 border-l-4 border-yellow-400 p-4 mb-6">
          <div className="flex">
            <div className="flex-shrink-0">
              <svg className="h-5 w-5 text-yellow-400" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
              </svg>
            </div>
            <div className="ml-3">
              <p className="text-sm text-yellow-700">
                <strong>Important:</strong> This dashboard shows only messages flagged by our detection system.
                Not all concerning messages may be detected (false negatives). This is a monitoring tool, not
                a guarantee of complete safety. Parental supervision remains essential.
              </p>
            </div>
          </div>
        </div>

        {/* Alert Banner for Critical Incidents */}
        {criticalIncidents.length > 0 && (
          <div className="bg-red-100 border-2 border-red-500 rounded-lg p-4 mb-6 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-3xl">🚨</span>
              <div>
                <p className="font-bold text-red-900">
                  {criticalIncidents.length} Critical Alert{criticalIncidents.length > 1 ? 's' : ''}
                </p>
                <p className="text-sm text-red-700">Requires immediate attention</p>
              </div>
            </div>
            <button
              type="button"
              onClick={handleReviewNow}
              className="px-6 py-2 bg-red-600 text-white rounded-lg font-bold hover:bg-red-700 transition"
            >
              Review Now
            </button>
          </div>
        )}

        {/* Category Filter */}
        <CategoryFilter
          selectedCategories={selectedCategories}
          onCategoryToggle={handleCategoryToggle}
        />

        {/* Results Count */}
        <div className="mb-4">
          <p className="text-sm text-gray-600">
            Showing {visibleIncidents.length} incident{visibleIncidents.length !== 1 ? 's' : ''}
            {selectedCategories.length < 5 && ' (filtered)'}
          </p>
        </div>

        {/* State B: paired, zero real data */}
        {showEmptyIncidentsMessage && (
          <div className="bg-white rounded-lg border border-gray-200 p-8 text-center">
            <span className="text-5xl mb-3 block">🌱</span>
            <h3 className="text-xl font-bold text-gray-800 mb-2">
              No incidents yet
            </h3>
            <p className="text-gray-600">
              The dashboard will populate as your child uses SendWise.
            </p>
          </div>
        )}

        {/* Critical Incidents Section */}
        {criticalIncidents.length > 0 && (
          <div
            ref={criticalSectionRef}
            className={
              'mb-8 rounded-lg transition-all duration-500 scroll-mt-4 ' +
              (flashCritical
                ? 'ring-4 ring-red-400 ring-offset-4 ring-offset-blue-50 bg-red-50/40'
                : '')
            }
          >
            <h2 className="text-2xl font-bold text-gray-900 mb-4">🚨 Critical Incidents</h2>
            {criticalIncidents.map(incident => (
              <IncidentCard
                key={incident.id}
                incident={incident}
                userIdHash={incident.childId}
                onReviewed={handleReviewed}
              />
            ))}
          </div>
        )}

        {/* Other Incidents */}
        {otherIncidents.length > 0 && (
          <div>
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Recent Activity</h2>
            {otherIncidents.map(incident => (
              <IncidentCard
                key={incident.id}
                incident={incident}
                userIdHash={incident.childId}
                onReviewed={handleReviewed}
              />
            ))}
          </div>
        )}

        {/* Filter-only empty state (has data overall but filter hides all) */}
        {!showEmptyIncidentsMessage &&
          filteredIncidents.length === 0 &&
          incidents.length > 0 && (
            <div className="text-center py-12">
              <span className="text-6xl mb-4 block">🔍</span>
              <h3 className="text-2xl font-bold text-gray-700 mb-2">
                No Incidents Match Filter
              </h3>
              <p className="text-gray-600">
                Try adjusting your filters to see more incidents.
              </p>
            </div>
          )}
      </main>

      {/* Footer */}
      <footer className="bg-white border-t mt-12">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="flex items-center justify-between text-sm text-gray-600">
            <p>SendWise Parental Dashboard v1.0</p>
            <div className="flex gap-4">
              <a href="/privacy" className="hover:text-blue-600">Privacy Policy</a>
              <a href="/terms" className="hover:text-blue-600">Terms of Service</a>
              <a href="#" className="hover:text-blue-600">Get Help</a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}
