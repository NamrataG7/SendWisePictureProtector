import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { updateSession } from '@/utils/supabase/middleware';

/**
 * Protects parent-facing pages and read-only APIs using Supabase auth.
 *
 * Protected (require Supabase session):
 *   - /                          (incident feed)
 *   - /insights/*
 *   - /pair
 *   - GET  /api/violations/[hash]
 *   - GET  /api/insights/[hash]
 *   - GET  /api/parent/*
 *   - POST /api/pairing/redeem   (parent_id derived from session, never body)
 *
 * Public (always):
 *   - /login, /signup, /auth/callback
 *   - /api/pairing/generate      (device → server; unauthenticated by design)
 *   - POST /api/violations       (device ingest, unauthenticated by design)
 *   - /api/dev/*                 (token-gated; the route enforces x-seed-token)
 *   - /privacy, /terms
 *   - Static assets (_next, favicon, images)
 */
export async function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const method = req.method.toUpperCase();

  // Always-public paths
  if (
    pathname.startsWith('/_next') ||
    pathname.startsWith('/favicon') ||
    pathname === '/login' ||
    pathname === '/signup' ||
    pathname.startsWith('/auth/callback') ||
    pathname === '/privacy' ||
    pathname === '/terms' ||
    pathname === '/api/pairing/generate'
  ) {
    return NextResponse.next();
  }

  // /api/dev/* — dev/demo seeder. Token-gated in the handler itself.
  if (pathname.startsWith('/api/dev/')) {
    if (
      process.env.NODE_ENV === 'production' &&
      process.env.ALLOW_SEED !== 'true'
    ) {
      return NextResponse.json({ error: 'Not Found' }, { status: 404 });
    }
    return NextResponse.next();
  }

  // POST /api/violations — device ingest, unauthenticated by design
  if (pathname.startsWith('/api/violations') && method === 'POST') {
    return NextResponse.next();
  }

  const isProtectedPage =
    pathname === '/' ||
    pathname.startsWith('/insights') ||
    pathname.startsWith('/pair');

  const isProtectedApi =
    (pathname.startsWith('/api/violations') && method === 'GET') ||
    (pathname.startsWith('/api/insights') && method === 'GET') ||
    (pathname.startsWith('/api/parent/') && method === 'GET') ||
    (pathname === '/api/pairing/redeem' && method === 'POST');

  // Always refresh the Supabase session so tokens rotate on every request.
  const { response, user } = await updateSession(req);

  if (!isProtectedPage && !isProtectedApi) {
    return response;
  }

  if (user) {
    return response;
  }

  // Unauthenticated
  if (isProtectedApi) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const loginUrl = new URL('/login', req.url);
  loginUrl.searchParams.set('callbackUrl', pathname);
  return NextResponse.redirect(loginUrl);
}

export const config = {
  matcher: [
    '/((?!_next/static|_next/image|favicon.ico|.*\\.(?:png|jpg|jpeg|svg|gif|webp|ico)$).*)',
  ],
};
