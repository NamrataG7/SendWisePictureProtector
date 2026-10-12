import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { createClient } from '@/utils/supabase/server';
import { isChildOfParent, unlinkChild } from '@/lib/parent-store';

/**
 * DELETE /api/parent/children/[user_id_hash]
 *
 * Unlink a child device from the currently signed-in parent's account.
 * Requires an authenticated Supabase session and that the target
 * user_id_hash is currently a member of parent:{user.id}:children.
 *
 * Also wipes the child's violation history (privacy hygiene — orphaned
 * records under an unpaired hash serve no purpose and could leak data
 * if the same hash is later re-paired to a different parent).
 */
export async function DELETE(
  _req: Request,
  { params }: { params: { user_id_hash: string } },
) {
  const supabase = createClient(await cookies());
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const hash = params.user_id_hash;
  if (!/^[a-f0-9]{64}$/i.test(hash)) {
    return NextResponse.json(
      { error: 'Invalid user_id_hash format' },
      { status: 400 },
    );
  }

  // Membership check — a parent must not be able to unlink somebody else's child.
  const owned = await isChildOfParent(user.id, hash);
  if (!owned) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  const { removed, hadHistory } = await unlinkChild(user.id, hash);
  return NextResponse.json({ ok: true, removed, hadHistory });
}
