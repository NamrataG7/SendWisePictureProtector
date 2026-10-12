'use client';

import DonutCard from './DonutCard';
import type { DonutSlice } from '@/lib/insights-aggregates';

export default function CategoryDistributionCard({
  slices,
  total,
}: {
  slices: DonutSlice[];
  total: number;
}) {
  return <DonutCard title="Category Distribution" slices={slices} total={total} />;
}
