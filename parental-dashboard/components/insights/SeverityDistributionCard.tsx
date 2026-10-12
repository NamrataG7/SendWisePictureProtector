'use client';

import DonutCard from './DonutCard';
import type { DonutSlice } from '@/lib/insights-aggregates';

export default function SeverityDistributionCard({
  slices,
  total,
}: {
  slices: DonutSlice[];
  total: number;
}) {
  return <DonutCard title="Severity Distribution" slices={slices} total={total} />;
}
