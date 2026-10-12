/**
 * Server-only insights aggregation.
 *
 * Shared by:
 *   - GET /api/insights/[user_id_hash]  (HTTP)
 *   - /insights server component        (direct call, no round-trip)
 *
 * NOTE: We would normally use `import 'server-only'` here, but that package
 * is not a declared dependency in this workspace. We enforce the same
 * invariant at runtime with a window guard so accidental client imports
 * fail loudly instead of silently shipping Redis code to the browser.
 */

if (typeof window !== 'undefined') {
  throw new Error(
    '[insights-server] This module is server-only and must not be imported from client components.',
  );
}

import { redis } from '@/lib/redis';
import type { IncidentCategoryT, SeverityT, ActionT } from '@/lib/schema';
import type {
  InsightsPayload,
  InsightsTrendPoint,
} from '@/lib/insights-server-types';
import type { Incident, DashboardStats } from '@/lib/types';

export type {
  InsightsPayload,
  InsightsTrendPoint as TrendPoint,
} from '@/lib/insights-server-types';

interface StoredViolation {
  user_id_hash: string;
  timestamp: string;
  category: IncidentCategoryT;
  severity: SeverityT;
  action: ActionT;
  session_id: string;
}

const CATEGORIES: IncidentCategoryT[] = [
  'harassment',
  'threats',
  'hate_speech',
  'sexual_content',
  'self_harm',
  'sensitive_image',
];
const SEVERITIES: SeverityT[] = ['low', 'medium', 'high'];

function dayKey(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * Compute the 30-day insights payload for a single user_id_hash.
 * Same shape returned by GET /api/insights/[user_id_hash].
 */
export async function computeInsights(user_id_hash: string): Promise<InsightsPayload> {
  const raw = await redis.lrange(`violations:${user_id_hash}`, 0, -1);
  const violations: StoredViolation[] = raw
    .map((s) => {
      try {
        return JSON.parse(s) as StoredViolation;
      } catch {
        return null;
      }
    })
    .filter((v): v is StoredViolation => v !== null);

  const now = new Date();
  const trendMap = new Map<string, number>();
  for (let i = 29; i >= 0; i--) {
    const d = new Date(now);
    d.setUTCDate(now.getUTCDate() - i);
    trendMap.set(dayKey(d), 0);
  }
  const cutoffMs = now.getTime() - 30 * 24 * 60 * 60 * 1000;

  const categoryDistribution = Object.fromEntries(
    CATEGORIES.map((c) => [c, 0]),
  ) as Record<IncidentCategoryT, number>;
  const severityDistribution = Object.fromEntries(
    SEVERITIES.map((s) => [s, 0]),
  ) as Record<SeverityT, number>;
  const editedVsSent = { edited: 0, sent_anyway: 0 };

  for (const v of violations) {
    const t = Date.parse(v.timestamp);
    if (!Number.isNaN(t) && t >= cutoffMs) {
      const key = dayKey(new Date(t));
      if (trendMap.has(key)) trendMap.set(key, (trendMap.get(key) ?? 0) + 1);
    }
    if (v.category in categoryDistribution) categoryDistribution[v.category] += 1;
    if (v.severity in severityDistribution) severityDistribution[v.severity] += 1;
    if (v.action === 'edited') editedVsSent.edited += 1;
    else if (v.action === 'sent_anyway') editedVsSent.sent_anyway += 1;
  }

  const trend: InsightsTrendPoint[] = Array.from(trendMap.entries()).map(
    ([date, count]) => ({ date, count }),
  );

  return {
    user_id_hash,
    total: violations.length,
    trend,
    categoryDistribution,
    severityDistribution,
    editedVsSent,
  };
}

/**
 * Aggregate insights across many children by summing counts and merging
 * daily trend points. Categories, severities, and edited/sent tallies are
 * summed; trend is aligned by ISO date key.
 */
export async function computeInsightsAggregate(
  user_id_hashes: string[],
): Promise<InsightsPayload> {
  if (user_id_hashes.length === 1) return computeInsights(user_id_hashes[0]);

  const parts = await Promise.all(user_id_hashes.map(computeInsights));

  const trendMap = new Map<string, number>();
  const categoryDistribution = Object.fromEntries(
    CATEGORIES.map((c) => [c, 0]),
  ) as Record<IncidentCategoryT, number>;
  const severityDistribution = Object.fromEntries(
    SEVERITIES.map((s) => [s, 0]),
  ) as Record<SeverityT, number>;
  const editedVsSent = { edited: 0, sent_anyway: 0 };
  let total = 0;

  for (const p of parts) {
    total += p.total;
    for (const pt of p.trend) {
      trendMap.set(pt.date, (trendMap.get(pt.date) ?? 0) + pt.count);
    }
    for (const c of CATEGORIES) categoryDistribution[c] += p.categoryDistribution[c] ?? 0;
    for (const s of SEVERITIES) severityDistribution[s] += p.severityDistribution[s] ?? 0;
    editedVsSent.edited += p.editedVsSent.edited;
    editedVsSent.sent_anyway += p.editedVsSent.sent_anyway;
  }

  const trend: InsightsTrendPoint[] = Array.from(trendMap.entries())
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([date, count]) => ({ date, count }));

  return {
    user_id_hash: user_id_hashes.join(','),
    total,
    trend,
    categoryDistribution,
    severityDistribution,
    editedVsSent,
  };
}

/**
 * Recommendation copy shown alongside each real incident. Keyed by the
 * canonical 6 categories the API accepts (see lib/schema.ts).
 */
const RECOMMENDATION_BY_CATEGORY: Record<IncidentCategoryT, string> = {
  self_harm:
    'URGENT: Contact emergency services (988 Suicide & Crisis Lifeline in US) if immediate danger. Reach out to your child with compassion.',
  threats:
    'Talk to your child about the incident. Consider contacting the school or law enforcement if threats persist.',
  harassment:
    'Discuss healthy communication with your child. Provide guidance on responding to conflict.',
  hate_speech:
    'Have a conversation about respect and inclusivity. Explain the impact of biased language.',
  sexual_content:
    'Talk about online safety and privacy. Ensure they know how to block/report unwanted contact.',
  sensitive_image:
    'Talk about sharing images online and consent. Review who they are messaging and discuss safe image-sharing boundaries.',
};

/**
 * Build a display-ready incident list across one or more children by
 * LRANGE-ing each `violations:{hash}` list, parsing, sorting newest-first,
 * and capping at the 50 most-recent entries.
 *
 * Redis violations only carry metadata (no text) — the Incident type keeps
 * `platform` because the UI expects it, but we set it to "other" since the
 * ingest schema doesn't record platform.
 */
export async function computeIncidentList(
  user_id_hashes: string[],
): Promise<Incident[]> {
  if (user_id_hashes.length === 0) return [];

  const lists = await Promise.all(
    user_id_hashes.map((h) => redis.lrange(`violations:${h}`, 0, -1)),
  );

  const all: Incident[] = [];
  for (const raw of lists) {
    for (let i = 0; i < raw.length; i++) {
      let v: StoredViolation | null = null;
      try {
        v = JSON.parse(raw[i]) as StoredViolation;
      } catch {
        continue;
      }
      if (!v) continue;
      const ts = new Date(v.timestamp);
      if (Number.isNaN(ts.getTime())) continue;

      all.push({
        id: `${v.user_id_hash}:${v.session_id}:${v.timestamp}:${i}`,
        childId: v.user_id_hash,
        timestamp: ts,
        // Ingest schema does not record platform; use a neutral display value.
        platform: 'other',
        category: v.category,
        severity: v.severity,
        action: v.action,
        reviewed: (v as StoredViolation & { reviewed?: boolean }).reviewed === true,
        detections: [
          {
            type: v.category,
            matches: [v.severity],
          },
        ],
        recommendation:
          RECOMMENDATION_BY_CATEGORY[v.category] ??
          'Talk to your child about safe online communication.',
      });
    }
  }

  all.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());
  return all.slice(0, 50);
}

/**
 * Roll incidents into the 4 headline stats tiles on the dashboard home.
 * Severity mapping (per ingest schema which uses low/medium/high):
 *   - criticalIncidents     = severity === 'high'
 *   - highPriorityIncidents = severity === 'medium'
 *   - messagesPrevented     = action in {edited, cancelled, blocked}
 */
export function computeDashboardStats(incidents: Incident[]): DashboardStats {
  let critical = 0;
  let high = 0;
  let prevented = 0;
  let lastTs: Date | undefined;

  for (const inc of incidents) {
    if (inc.severity === 'high') critical += 1;
    else if (inc.severity === 'medium') high += 1;
    if (
      inc.action === 'edited' ||
      inc.action === 'cancelled' ||
      inc.action === 'blocked'
    ) {
      prevented += 1;
    }
    if (!lastTs || inc.timestamp > lastTs) lastTs = inc.timestamp;
  }

  return {
    totalIncidents: incidents.length,
    criticalIncidents: critical,
    highPriorityIncidents: high,
    messagesPrevented: prevented,
    lastIncidentTime: lastTs,
  };
}
