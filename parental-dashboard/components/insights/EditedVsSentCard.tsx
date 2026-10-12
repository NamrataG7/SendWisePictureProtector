'use client';

import DonutCard from './DonutCard';
import type { DonutSlice } from '@/lib/insights-aggregates';

export default function EditedVsSentCard({
  slices,
  total,
}: {
  slices: DonutSlice[];
  total: number;
}) {
  return <DonutCard title="Edited vs Sent Unchanged" slices={slices} total={total} />;
}
