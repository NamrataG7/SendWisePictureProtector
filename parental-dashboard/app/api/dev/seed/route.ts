/**
 * POST /api/dev/seed
 *
 * Development / demo seeder: pre-populates a parent account with a demo child
 * and realistic-looking violations so a fresh Vercel deploy renders a working
 * Fig 3 dashboard out of the box (paper reproducibility).
 *
 * SECURITY:
 *   - Requires header `x-seed-token` matching env `SEED_TOKEN`.
 *   - Returns 404 in production unless `ALLOW_SEED === 'true'` (belt-and-
 *     suspenders on top of the token check).
 *   - Middleware bypasses auth for /api/dev/* — token is the only gate.
 *
 * Body (one of `parent_id` or `parent_email` is required):
 *   {
 *     parent_id?: string,             // Supabase user UUID (preferred)
 *     parent_email?: string,          // Falls back to Admin API lookup
 *     child_name?: string,            // Optional label
 *     num_violations?: number = 40,
 *     days_range?: number = 30
 *   }
 *
 * When only `parent_email` is provided we attempt to resolve it via the
 * Supabase Admin API (`auth.admin.listUsers`) — this requires
 * `SUPABASE_SERVICE_ROLE_KEY` in env. Without that key the endpoint returns
 * 400 with a message asking the caller to pass `parent_id` directly.
 *
 * Response:
 *   { ok: true, user_id_hash, seeded, parent, child }
 */

import { NextRequest, NextResponse } from 'next/server';
import { createClient as createAdminClient } from '@supabase/supabase-js';
import { redis } from '@/lib/redis';
import {
  computeChildHash,
  generateViolations,
} from '@/scripts/generate-seed-data';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const LIST_CAP = 1000;
const DEFAULT_COUNT = 40;
const DEFAULT_DAYS = 30;
const MAX_COUNT = 500;
const MAX_DAYS = 365;

function isProdBlocked(): boolean {
  return process.env.NODE_ENV === 'production' && process.env.ALLOW_SEED !== 'true';
}

async function resolveParentIdFromEmail(email: string): Promise<string | null> {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!url || !serviceKey) return null;

  const admin = createAdminClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  // Paginate until we find a match or run out of pages. Small deploys are
  // fine on page 1; this is a dev tool so we cap at 10 pages.
  const target = email.trim().toLowerCase();
  for (let page = 1; page <= 10; page++) {
    const { data, error } = await admin.auth.admin.listUsers({ page, perPage: 200 });
    if (error) return null;
    const match = data.users.find((u) => (u.email ?? '').toLowerCase() === target);
    if (match) return match.id;
    if (data.users.length < 200) break;
  }
  return null;
}

export async function POST(req: NextRequest) {
  if (isProdBlocked()) {
    return new NextResponse('Not Found', { status: 404 });
  }

  const expected = process.env.SEED_TOKEN;
  const provided = req.headers.get('x-seed-token');
  if (!expected || !provided || provided !== expected) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  let body: {
    parent_id?: unknown;
    parent_email?: unknown;
    child_name?: unknown;
    num_violations?: unknown;
    days_range?: unknown;
  };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 });
  }

  const parentIdRaw = typeof body.parent_id === 'string' ? body.parent_id.trim() : '';
  const parentEmailRaw =
    typeof body.parent_email === 'string' ? body.parent_email.trim().toLowerCase() : '';

  let parentId = parentIdRaw;
  let parentLabel = parentIdRaw;

  if (!parentId) {
    if (!parentEmailRaw || !parentEmailRaw.includes('@')) {
      return NextResponse.json(
        { error: 'parent_id or parent_email required' },
        { status: 400 },
      );
    }
    const resolved = await resolveParentIdFromEmail(parentEmailRaw);
    if (!resolved) {
      return NextResponse.json(
        {
          error:
            'Could not resolve parent_email to a Supabase user. Either pass parent_id explicitly, or set SUPABASE_SERVICE_ROLE_KEY in the environment so the seeder can call auth.admin.listUsers.',
        },
        { status: 400 },
      );
    }
    parentId = resolved;
    parentLabel = parentEmailRaw;
  }

  const childName =
    typeof body.child_name === 'string' && body.child_name.trim().length > 0
      ? body.child_name.trim()
      : 'Demo Child';

  const numViolations = clampInt(body.num_violations, DEFAULT_COUNT, 1, MAX_COUNT);
  const daysRange = clampInt(body.days_range, DEFAULT_DAYS, 1, MAX_DAYS);

  const userIdHash = computeChildHash(parentId);

  // Link child to parent (matches /api/pairing/redeem key convention).
  await redis.sadd(`parent:${parentId}:children`, userIdHash);

  // Wipe any prior demo data for this child so repeated calls are idempotent.
  await redis.del(`violations:${userIdHash}`);

  const violations = generateViolations({
    userIdHash,
    count: numViolations,
    daysRange,
    seed: `${parentId}:${childName}:${numViolations}:${daysRange}`,
  });

  // LPUSH oldest→newest so the newest ends up at index 0 (matches real ingest).
  const oldestFirst = [...violations].reverse();
  if (oldestFirst.length > 0) {
    const payloads = oldestFirst.map((v) => JSON.stringify(v));
    await redis.lpush(`violations:${userIdHash}`, ...payloads);
    await redis.ltrim(`violations:${userIdHash}`, 0, LIST_CAP - 1);
  }

  return NextResponse.json({
    ok: true,
    user_id_hash: userIdHash,
    seeded: violations.length,
    parent: parentLabel,
    child: childName,
  });
}

function clampInt(v: unknown, fallback: number, min: number, max: number): number {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? parseInt(v, 10) : NaN;
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.floor(n)));
}
