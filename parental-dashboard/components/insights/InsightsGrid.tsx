'use client';

import InterventionTrendCard from './InterventionTrendCard';
import CategoryDistributionCard from './CategoryDistributionCard';
import SeverityDistributionCard from './SeverityDistributionCard';
import EditedVsSentCard from './EditedVsSentCard';
import type { TrendPoint, DonutSlice } from '@/lib/insights-aggregates';

export interface InsightsGridProps {
  total: number;
  trend: TrendPoint[];
  categoryDistribution: DonutSlice[];
  severityDistribution: DonutSlice[];
  editedVsSent: DonutSlice[];
}

export default function InsightsGrid({
  total,
  trend,
  categoryDistribution,
  severityDistribution,
  editedVsSent,
}: InsightsGridProps) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
      <InterventionTrendCard data={trend} />
      <CategoryDistributionCard slices={categoryDistribution} total={total} />
      <SeverityDistributionCard slices={severityDistribution} total={total} />
      <EditedVsSentCard slices={editedVsSent} total={total} />
    </div>
  );
}
