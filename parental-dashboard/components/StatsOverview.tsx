import { DashboardStats } from '@/lib/types';

interface StatsOverviewProps {
  stats: DashboardStats;
}

export default function StatsOverview({ stats }: StatsOverviewProps) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
      {/* Critical Incidents */}
      <div className="bg-red-50 border-2 border-red-200 rounded-lg p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-red-600 text-sm font-semibold uppercase">Critical</p>
            <p className="text-3xl font-bold text-red-700">{stats.criticalIncidents}</p>
          </div>
          <span className="text-4xl">🚨</span>
        </div>
        <p className="text-xs text-red-600 mt-2">Requires immediate action</p>
      </div>

      {/* High Priority */}
      <div className="bg-orange-50 border-2 border-orange-200 rounded-lg p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-orange-600 text-sm font-semibold uppercase">High Priority</p>
            <p className="text-3xl font-bold text-orange-700">{stats.highPriorityIncidents}</p>
          </div>
          <span className="text-4xl">⚠️</span>
        </div>
        <p className="text-xs text-orange-600 mt-2">Needs attention soon</p>
      </div>

      {/* Messages Prevented */}
      <div className="bg-green-50 border-2 border-green-200 rounded-lg p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-green-600 text-sm font-semibold uppercase">Prevented</p>
            <p className="text-3xl font-bold text-green-700">{stats.messagesPrevented}</p>
          </div>
          <span className="text-4xl">✅</span>
        </div>
        <p className="text-xs text-green-600 mt-2">Harmful messages stopped</p>
      </div>

      {/* Total Incidents */}
      <div className="bg-blue-50 border-2 border-blue-200 rounded-lg p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-blue-600 text-sm font-semibold uppercase">Total</p>
            <p className="text-3xl font-bold text-blue-700">{stats.totalIncidents}</p>
          </div>
          <span className="text-4xl">📊</span>
        </div>
        <p className="text-xs text-blue-600 mt-2">Last 7 days</p>
      </div>
    </div>
  );
}
