/**
 * Parent → children lookup.
 *
 * Backed by Redis set `parent:{parentId}:children` (populated by
 * /api/pairing/redeem). Server-only: relies on the redis singleton.
 *
 * NOTE (Supabase migration): `parentId` is now the Supabase user's UUID
 * (from `auth.getUser().data.user.id`) — no longer an email address.
 *
 * Any pre-migration Redis keys of the form `parent:{email}:children` are
 * effectively orphaned and unreachable, because the parent identifier has
 * changed shape. This is intentional: at time of migration there were no
 * real users, so we do not attempt a data migration. If you need one later,
 * you would enumerate the old keys and rewrite them under the parent's
 * Supabase UUID looked up via `auth.admin.listUsers()`.
 */

if (typeof window !== 'undefined') {
  throw new Error(
    '[parent-store] This module is server-only and must not be imported from client components.',
  );
}

import { redis } from '@/lib/redis';

function parentKey(parentId: string): string {
  return `parent:${parentId}:children`;
}

/**
 * Return the list of user_id_hashes linked to the given parent (by Supabase UUID).
 * Empty array when the parent has no linked children.
 */
export async function getChildrenForParent(parentId: string): Promise<string[]> {
  if (!parentId) return [];
  const members = await redis.smembers(parentKey(parentId));
  // Defensive: only return well-formed user_id_hashes.
  return members.filter((m) => /^[a-f0-9]{64}$/i.test(m));
}

/**
 * Membership check: is `user_id_hash` linked to `parentId`?
 *
 * Used by read APIs (violations, insights) to prevent IDOR — any signed-in
 * parent must only be able to read data for children they have paired.
 */
export async function isChildOfParent(
  parentId: string | null | undefined,
  user_id_hash: string,
): Promise<boolean> {
  if (!parentId || !user_id_hash) return false;
  if (!/^[a-f0-9]{64}$/i.test(user_id_hash)) return false;
  const children = await getChildrenForParent(parentId);
  return children.includes(user_id_hash);
}

/**
 * Remove a child device from the parent's linked set. Also wipes the
 * child's violation history (privacy hygiene — orphaned records under
 * an unpaired hash serve no purpose and could leak data if the same hash
 * is later re-paired to a different parent).
 *
 * Returns { removed: number, hadHistory: boolean } for UI feedback.
 */
export async function unlinkChild(
  parentId: string,
  user_id_hash: string,
): Promise<{ removed: number; hadHistory: boolean }> {
  if (!parentId || !user_id_hash) return { removed: 0, hadHistory: false };
  if (!/^[a-f0-9]{64}$/i.test(user_id_hash)) {
    return { removed: 0, hadHistory: false };
  }
  const removed = await redis.srem(parentKey(parentId), user_id_hash);
  const violationsKey = `violations:${user_id_hash}`;
  const hadHistory = (await redis.llen(violationsKey)) > 0;
  if (hadHistory) {
    await redis.del(violationsKey);
  }
  return { removed, hadHistory };
}
