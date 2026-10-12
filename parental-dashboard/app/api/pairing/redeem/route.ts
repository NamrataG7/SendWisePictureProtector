import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { z } from 'zod';
import { createClient } from '@/utils/supabase/server';
import { redis } from '@/lib/redis';

export const runtime = 'nodejs';

/**
 * POST /api/pairing/redeem
 *
 * Auth model:
 *   - Requires an authenticated Supabase parent session (401 otherwise).
 *   - parent_id is derived from `user.id` (Supabase UUID). Any `parent_id`
 *     sent in the body is rejected via `.strict()` — an attacker cannot
 *     claim another parent's linkage by forging the body.
 *
 * Rate limits (both Redis INCR+EXPIRE):
 *   - Per parent: 5 redeem attempts / hour  (429 on excess).
 *   - Per code:   5 wrong attempts / 15-min TTL. On the 5th wrong attempt
 *     the pairing key is deleted so the code becomes unusable.
 *
 * Body: { code: 6-digit string, child_name?: string }.
 */
const RedeemBodySchema = z
  .object({
    code: z.string().regex(/^\d{6}$/, 'code must be 6 digits'),
    child_name: z.string().min(1).max(64).optional(),
  })
  .strict(); // rejects unknown keys → parent_id in body ⇒ 400

const MAX_REDEEM_PER_PARENT_PER_HOUR = 5;
const MAX_WRONG_PER_CODE = 5;

export async function POST(req: NextRequest) {
  // 1) Require Supabase session.
  const supabase = createClient(await cookies());
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  const parent_id = user.id;

  // 2) Parse body (strict — parent_id present ⇒ 400).
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 });
  }
  const parsed = RedeemBodySchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json(
      { error: 'Validation failed', details: parsed.error.flatten() },
      { status: 400 },
    );
  }
  const { code } = parsed.data;

  // 3) Per-parent rate limit: 5 redeem attempts / hour.
  const parentRateKey = `ratelimit:pairing_redeem:${parent_id}`;
  const parentAttempts = await redis.incr(parentRateKey);
  if (parentAttempts === 1) {
    await redis.expire(parentRateKey, 3600);
  }
  if (parentAttempts > MAX_REDEEM_PER_PARENT_PER_HOUR) {
    return NextResponse.json(
      { error: 'Too many pairing attempts. Try again later.' },
      { status: 429 },
    );
  }

  // 4) Look up the code.
  const key = `pairing:${code}`;
  const user_id_hash = await redis.get(key);
  if (!user_id_hash) {
    const codeRateKey = `ratelimit:pairing_code:${code}`;
    const wrong = await redis.incr(codeRateKey);
    if (wrong === 1) {
      await redis.expire(codeRateKey, 900); // match pairing code TTL
    }
    if (wrong >= MAX_WRONG_PER_CODE) {
      await redis.del(key);
      return NextResponse.json(
        { error: 'Too many attempts for this code.' },
        { status: 429 },
      );
    }
    return NextResponse.json({ error: 'Invalid or expired code' }, { status: 404 });
  }

  // 5) One-time use: link child to parent (by Supabase UUID) then invalidate code.
  await redis.sadd(`parent:${parent_id}:children`, user_id_hash);
  await redis.del(key);

  return NextResponse.json({ ok: true, user_id_hash });
}
