import { NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { createClient } from '@/utils/supabase/server';
import { getChildrenForParent } from '@/lib/parent-store';

export const runtime = 'nodejs';

/**
 * GET /api/parent/children
 * Returns the set of user_id_hashes linked to the authenticated parent.
 * Middleware also protects this path; we re-check here for defence in depth.
 */
export async function GET() {
  const supabase = createClient(await cookies());
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  const children = await getChildrenForParent(user.id);
  return NextResponse.json({ children });
}
