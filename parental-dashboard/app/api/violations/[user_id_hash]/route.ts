import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { createClient } from '@/utils/supabase/server';
import { isChildOfParent } from '@/lib/parent-store';
import { redis } from '@/lib/redis';

export const runtime = 'nodejs';

/**
 * GET /api/violations/[user_id_hash]
 *
 * Auth model:
 *   - Requires an authenticated Supabase parent session (401 otherwise).
 *   - Parent must be linked to `user_id_hash` via the pairing set
 *     `parent:{user.id}:children` (403 otherwise).
 */
export async function GET(
  _req: NextRequest,
  { params }: { params: { user_id_hash: string } },
) {
  const { user_id_hash } = params;
  if (!/^[a-f0-9]{64}$/i.test(user_id_hash)) {
    return NextResponse.json({ error: 'Invalid user_id_hash' }, { status: 400 });
  }

  const supabase = createClient(await cookies());
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const allowed = await isChildOfParent(user.id, user_id_hash);
  if (!allowed) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  const raw = await redis.lrange(`violations:${user_id_hash}`, 0, -1);
  const violations = raw
    .map((s) => {
      try {
        return JSON.parse(s);
      } catch {
        return null;
      }
    })
    .filter((v): v is Record<string, unknown> => v !== null);

  return NextResponse.json({ user_id_hash, count: violations.length, violations });
}
