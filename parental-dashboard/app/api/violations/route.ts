import { NextRequest, NextResponse } from 'next/server';
import { redis } from '@/lib/redis';
import {
  ViolationIngestSchema,
  FORBIDDEN_CONTENT_FIELDS,
} from '@/lib/schema';

export const runtime = 'nodejs';

const RATE_LIMIT_PER_HOUR = 100;
const LIST_CAP = 1000;

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 });
  }

  // Privacy guard: reject any content-bearing fields outright.
  if (body && typeof body === 'object') {
    for (const forbidden of FORBIDDEN_CONTENT_FIELDS) {
      if (forbidden in (body as Record<string, unknown>)) {
        return NextResponse.json(
          {
            error: `Field "${forbidden}" is not permitted. This endpoint accepts metadata only.`,
          },
          { status: 400 },
        );
      }
    }
  }

  const parsed = ViolationIngestSchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json(
      { error: 'Validation failed', details: parsed.error.flatten() },
      { status: 400 },
    );
  }
  const v = parsed.data;

  // Rate limit: 100 req/hour per user_id_hash
  const rlKey = `ratelimit:violations:${v.user_id_hash}`;
  const count = await redis.incr(rlKey);
  if (count === 1) {
    await redis.expire(rlKey, 3600);
  }
  if (count > RATE_LIMIT_PER_HOUR) {
    const ttl = await redis.ttl(rlKey);
    return NextResponse.json(
      { error: 'Rate limit exceeded', retry_after_seconds: ttl },
      { status: 429 },
    );
  }

  const listKey = `violations:${v.user_id_hash}`;
  await redis.lpush(listKey, JSON.stringify(v));
  await redis.ltrim(listKey, 0, LIST_CAP - 1);

  return NextResponse.json({ ok: true });
}
