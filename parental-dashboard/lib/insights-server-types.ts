/**
 * Pure type declarations shared between server-only insights code
 * and the client-safe aggregate/converter helpers. No runtime imports.
 */

import type { IncidentCategoryT, SeverityT } from '@/lib/schema';

export interface InsightsTrendPoint {
  date: string; // YYYY-MM-DD
  count: number;
}

export interface InsightsPayload {
  user_id_hash: string;
  total: number;
  trend: InsightsTrendPoint[];
  categoryDistribution: Record<IncidentCategoryT, number>;
  severityDistribution: Record<SeverityT, number>;
  editedVsSent: { edited: number; sent_anyway: number };
}
