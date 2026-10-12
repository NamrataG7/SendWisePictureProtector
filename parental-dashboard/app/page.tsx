import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { createClient } from '@/utils/supabase/server';
import DashboardClient from './dashboard-client';
import EmptyDashboardState from '@/components/EmptyDashboardState';
import SignOutButton from '@/components/SignOutButton';
import { getChildrenForParent } from '@/lib/parent-store';
import {
  computeIncidentList,
  computeDashboardStats,
} from '@/lib/insights-server';

export const dynamic = 'force-dynamic';

export default async function DashboardPage() {
  const supabase = createClient(await cookies());
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    redirect('/login');
  }

  const metadata = (user.user_metadata ?? {}) as { full_name?: string; name?: string };
  const parentLabel = metadata.full_name || metadata.name || user.email || 'Parent';

  // Real data path: look up this parent's linked children in Redis.
  const children = await getChildrenForParent(user.id);

  // State A — no children paired. Show the empty state; skip Redis entirely.
  if (children.length === 0) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100">
        <header className="bg-white shadow-md">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between">
            <div>
              <h1 className="text-3xl font-bold text-gray-900">SendWise</h1>
              <p className="text-sm text-gray-600">Parental Dashboard — {parentLabel}</p>
            </div>
            <div className="flex items-center gap-4">
              <div className="text-sm text-gray-500">No child device linked</div>
              <SignOutButton />
            </div>
          </div>
        </header>
        <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
          <div className="bg-yellow-50 border-l-4 border-yellow-400 p-4 mb-6">
            <p className="text-sm text-yellow-700">
              <strong>Important:</strong> SendWise is a monitoring tool, not a
              guarantee of complete safety. Parental supervision remains essential.
            </p>
          </div>
          <EmptyDashboardState />
          <div className="text-center mt-6">
            <a href="/insights" className="text-sm text-purple-700 hover:underline">
              View Insights →
            </a>
          </div>
        </main>
      </div>
    );
  }

  // State B / C — paired. Aggregate Redis violations across children.
  const incidents = await computeIncidentList(children);
  const stats = computeDashboardStats(incidents);

  return (
    <DashboardClient
      parentLabel={parentLabel}
      childCount={children.length}
      childHashes={children}
      incidents={incidents}
      stats={stats}
    />
  );
}
