import { NextRequest, NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { createClient } from '@/utils/supabase/server';
import { isChildOfParent } from '@/lib/parent-store';
import { computeInsights } from '@/lib/insights-server';

export const runtime = 'nodejs';

/**
 * GET /api/insights/[user_id_hash]
 *
 * Auth model matches /api/violations/[user_id_hash]:
 *   - 401 if no Supabase parent session.
 *   - 403 if parent is not linked to this child.
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

  const payload = await computeInsights(user_id_hash);
  return NextResponse.json(payload);
}
