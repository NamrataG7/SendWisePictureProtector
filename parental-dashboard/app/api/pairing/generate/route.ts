import { NextRequest, NextResponse } from 'next/server';
import { randomInt } from 'crypto';
import { redis } from '@/lib/redis';
import { PairingGenerateSchema } from '@/lib/schema';

export const runtime = 'nodejs';

const PAIRING_TTL_SECONDS = 15 * 60;

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 });
  }

  const parsed = PairingGenerateSchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json(
      { error: 'Validation failed', details: parsed.error.flatten() },
      { status: 400 },
    );
  }

  // Cryptographically-random 6-digit code, zero-padded.
  const code = randomInt(0, 1_000_000).toString().padStart(6, '0');
  await redis.set(`pairing:${code}`, parsed.data.user_id_hash, 'EX', PAIRING_TTL_SECONDS);

  const expiresAt = new Date(Date.now() + PAIRING_TTL_SECONDS * 1000).toISOString();
  return NextResponse.json({ code, expires_at: expiresAt });
}
