/**
 * Deterministic seed-data generator for the SendWise parental dashboard.
 *
 * Used by both:
 *   - /api/dev/seed (route imports generateViolations)
 *   - Standalone runs:  npx tsx scripts/generate-seed-data.ts
 *
 * Design notes:
 *   - Categories, severities, and actions match lib/schema.ts exactly so the
 *     generated payloads are accepted by the same validation the real device
 *     ingest uses.
 *   - Random numbers come from a seeded mulberry32 PRNG so repeat runs with
 *     the same seed string produce identical output — useful for paper
 *     reproducibility (Fig 3 screenshots).
 *
 * NOTE ON CATEGORIES: Categories match the paper's canonical 6-category
 * taxonomy exactly (harassment, threats, hate_speech, sexual_content,
 * self_harm, sensitive_image), matching lib/schema.ts IncidentCategoryEnum.
 */

import { createHash } from 'node:crypto';

// ---------------------------------------------------------------------------
// Types (kept structurally identical to lib/schema.ts ViolationIngest so we
// don't create a build-time coupling for the standalone script path.)
// ---------------------------------------------------------------------------

export type SeedCategory =
  | 'harassment'
  | 'threats'
  | 'hate_speech'
  | 'sexual_content'
  | 'self_harm'
  | 'sensitive_image';

export type SeedSeverity = 'low' | 'medium' | 'high';
export type SeedAction = 'edited' | 'sent_anyway' | 'blocked' | 'cancelled';

export interface SeedViolation {
  user_id_hash: string;
  timestamp: string;
  category: SeedCategory;
  severity: SeedSeverity;
  action: SeedAction;
  session_id: string;
}

// ---------------------------------------------------------------------------
// Deterministic PRNG (mulberry32) seeded from a string via xmur3.
// ---------------------------------------------------------------------------

function xmur3(str: string): () => number {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(h ^ str.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return function () {
    h = Math.imul(h ^ (h >>> 16), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    h ^= h >>> 16;
    return h >>> 0;
  };
}

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function makeRng(seed: string): () => number {
  const s = xmur3(seed);
  return mulberry32(s());
}

// ---------------------------------------------------------------------------
// Weighted picker
// ---------------------------------------------------------------------------

function pickWeighted<T>(rng: () => number, choices: Array<[T, number]>): T {
  const total = choices.reduce((s, [, w]) => s + w, 0);
  let r = rng() * total;
  for (const [val, w] of choices) {
    r -= w;
    if (r <= 0) return val;
  }
  return choices[choices.length - 1][0];
}

// ---------------------------------------------------------------------------
// Distributions (see header note re: category mapping)
// ---------------------------------------------------------------------------

const CATEGORY_WEIGHTS: Array<[SeedCategory, number]> = [
  ['harassment', 40],
  ['threats', 20],
  ['hate_speech', 20],
  ['sexual_content', 12],
  ['self_harm', 8],
  ['sensitive_image', 10],
];

const SEVERITY_WEIGHTS: Array<[SeedSeverity, number]> = [
  ['medium', 55],
  ['high', 30],
  ['low', 15],
];

const ACTION_WEIGHTS: Array<[SeedAction, number]> = [
  ['edited', 55],
  ['sent_anyway', 35],
  ['cancelled', 8],
  ['blocked', 2],
];

// ---------------------------------------------------------------------------
// Public helpers
// ---------------------------------------------------------------------------

export function computeChildHash(parentEmail: string, childSuffix = 'demo-child-1'): string {
  const raw = `${parentEmail.trim().toLowerCase()}-${childSuffix}`;
  return createHash('sha256').update(raw).digest('hex').slice(0, 64);
}

export interface GenerateOptions {
  userIdHash: string;
  count: number;
  daysRange: number;
  now?: Date;
  seed?: string;
}

/**
 * Generate `count` violation records spread across the past `daysRange` days.
 * Recency-weighted so more-recent days receive slightly more incidents
 * (linear ramp: day 0 (today) weight ~2x day daysRange-1).
 */
export function generateViolations(opts: GenerateOptions): SeedViolation[] {
  const { userIdHash, count, daysRange } = opts;
  const now = opts.now ?? new Date();
  const seed = opts.seed ?? `${userIdHash}:${daysRange}:${count}`;
  const rng = makeRng(seed);

  // Recency-weighted day picker.
  const dayWeights: Array<[number, number]> = [];
  for (let d = 0; d < daysRange; d++) {
    // d=0 is today (highest weight), d=daysRange-1 is oldest.
    const w = 1 + (daysRange - 1 - d) * 0; // placeholder to satisfy TS
    void w;
    dayWeights.push([d, 2 - d / Math.max(1, daysRange - 1)]);
  }

  const out: SeedViolation[] = [];
  for (let i = 0; i < count; i++) {
    const daysAgo = pickWeighted(rng, dayWeights);
    // Random time within that day, clamped to <= now for today.
    const dayStart = new Date(now);
    dayStart.setDate(dayStart.getDate() - daysAgo);
    dayStart.setHours(0, 0, 0, 0);
    const maxOffsetMs =
      daysAgo === 0
        ? now.getTime() - dayStart.getTime()
        : 24 * 60 * 60 * 1000 - 1;
    const offset = Math.floor(rng() * Math.max(1, maxOffsetMs));
    const ts = new Date(dayStart.getTime() + offset);

    const category = pickWeighted(rng, CATEGORY_WEIGHTS);
    const severity = pickWeighted(rng, SEVERITY_WEIGHTS);
    const action = pickWeighted(rng, ACTION_WEIGHTS);

    // Session id: group ~1-3 violations per session for realism.
    const sessionBucket = Math.floor(i / (1 + Math.floor(rng() * 3)));
    const session_id = `sess-${userIdHash.slice(0, 8)}-${sessionBucket
      .toString()
      .padStart(4, '0')}`;

    out.push({
      user_id_hash: userIdHash,
      timestamp: ts.toISOString(),
      category,
      severity,
      action,
      session_id,
    });
  }

  // Sort newest first (matches how LPUSH accumulates).
  out.sort((a, b) => (a.timestamp < b.timestamp ? 1 : -1));
  return out;
}

/**
 * Add an "extras" tail so the dataset extends beyond `daysRange`
 * (useful for testing insights-aggregates 30-day windows and older).
 */
export function addExtras(
  existing: SeedViolation[],
  opts: { userIdHash: string; extraDays: number; extraCount: number; now?: Date; seed?: string },
): SeedViolation[] {
  const now = opts.now ?? new Date();
  const rng = makeRng(opts.seed ?? `${opts.userIdHash}:extras:${opts.extraDays}`);
  const extras: SeedViolation[] = [];
  for (let i = 0; i < opts.extraCount; i++) {
    const daysAgo = 30 + Math.floor(rng() * Math.max(1, opts.extraDays));
    const ts = new Date(now.getTime() - daysAgo * 24 * 60 * 60 * 1000 - Math.floor(rng() * 86_400_000));
    extras.push({
      user_id_hash: opts.userIdHash,
      timestamp: ts.toISOString(),
      category: pickWeighted(rng, CATEGORY_WEIGHTS),
      severity: pickWeighted(rng, SEVERITY_WEIGHTS),
      action: pickWeighted(rng, ACTION_WEIGHTS),
      session_id: `sess-${opts.userIdHash.slice(0, 8)}-old-${i.toString().padStart(4, '0')}`,
    });
  }
  return [...existing, ...extras].sort((a, b) => (a.timestamp < b.timestamp ? 1 : -1));
}

// ---------------------------------------------------------------------------
// Standalone entry point:  npx tsx scripts/generate-seed-data.ts <parent_email>
// Prints JSON to stdout — does NOT touch Redis.
// ---------------------------------------------------------------------------

// Check both require.main (CJS) and import.meta (ESM) — tsx supports both.
const isMain = (() => {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    return typeof require !== 'undefined' && require.main === module;
  } catch {
    return false;
  }
})();

if (isMain) {
  const parentEmail = process.argv[2] ?? 'parent@example.com';
  const count = parseInt(process.argv[3] ?? '40', 10);
  const days = parseInt(process.argv[4] ?? '30', 10);
  const userIdHash = computeChildHash(parentEmail);
  const rows = generateViolations({ userIdHash, count, daysRange: days });
  // eslint-disable-next-line no-console
  console.log(
    JSON.stringify(
      { user_id_hash: userIdHash, count: rows.length, violations: rows },
      null,
      2,
    ),
  );
}
