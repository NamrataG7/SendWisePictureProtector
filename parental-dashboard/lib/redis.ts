/**
 * Redis client singleton for SendWise Parental Dashboard.
 *
 * If REDIS_URL is not set (local dev), falls back to an in-memory Map-backed
 * stub implementing the subset of ioredis commands used by our API routes:
 *   - incr, expire, ttl
 *   - lpush, ltrim, lrange, llen
 *   - set (with EX), get, del
 *   - sadd, smembers
 *
 * This keeps `npm run dev` working without a Redis server.
 */

import type { Redis as IORedis } from 'ioredis';

// The minimal command surface our routes use. Keep in sync with route handlers.
export interface RedisLike {
  incr(key: string): Promise<number>;
  expire(key: string, seconds: number): Promise<number>;
  ttl(key: string): Promise<number>;
  lpush(key: string, ...values: string[]): Promise<number>;
  ltrim(key: string, start: number, stop: number): Promise<'OK'>;
  lrange(key: string, start: number, stop: number): Promise<string[]>;
  lrem(key: string, count: number, value: string): Promise<number>;
  lset(key: string, index: number, value: string): Promise<'OK'>;
  llen(key: string): Promise<number>;
  set(key: string, value: string, mode?: 'EX', ttlSeconds?: number): Promise<'OK'>;
  get(key: string): Promise<string | null>;
  del(key: string): Promise<number>;
  sadd(key: string, ...members: string[]): Promise<number>;
  srem(key: string, ...members: string[]): Promise<number>;
  smembers(key: string): Promise<string[]>;
}

// ---------------- In-memory stub ----------------

type Entry =
  | { type: 'string'; value: string; expiresAt?: number }
  | { type: 'list'; value: string[]; expiresAt?: number }
  | { type: 'set'; value: Set<string>; expiresAt?: number };

class InMemoryRedis implements RedisLike {
  private store = new Map<string, Entry>();

  private isExpired(entry: Entry): boolean {
    return entry.expiresAt !== undefined && Date.now() > entry.expiresAt;
  }

  private getEntry(key: string): Entry | undefined {
    const entry = this.store.get(key);
    if (!entry) return undefined;
    if (this.isExpired(entry)) {
      this.store.delete(key);
      return undefined;
    }
    return entry;
  }

  async incr(key: string): Promise<number> {
    const entry = this.getEntry(key);
    let n = 0;
    if (entry && entry.type === 'string') {
      n = parseInt(entry.value, 10) || 0;
    }
    n += 1;
    this.store.set(key, {
      type: 'string',
      value: String(n),
      expiresAt: entry?.expiresAt,
    });
    return n;
  }

  async expire(key: string, seconds: number): Promise<number> {
    const entry = this.getEntry(key);
    if (!entry) return 0;
    entry.expiresAt = Date.now() + seconds * 1000;
    this.store.set(key, entry);
    return 1;
  }

  async ttl(key: string): Promise<number> {
    const entry = this.getEntry(key);
    if (!entry) return -2;
    if (entry.expiresAt === undefined) return -1;
    return Math.max(0, Math.floor((entry.expiresAt - Date.now()) / 1000));
  }

  async lpush(key: string, ...values: string[]): Promise<number> {
    const entry = this.getEntry(key);
    const list = entry && entry.type === 'list' ? entry.value : [];
    // LPUSH inserts each value at head individually, matching Redis order
    for (const v of values) list.unshift(v);
    this.store.set(key, { type: 'list', value: list, expiresAt: entry?.expiresAt });
    return list.length;
  }

  async ltrim(key: string, start: number, stop: number): Promise<'OK'> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'list') return 'OK';
    const len = entry.value.length;
    const s = start < 0 ? Math.max(0, len + start) : start;
    const e = stop < 0 ? len + stop : stop;
    entry.value = entry.value.slice(s, e + 1);
    this.store.set(key, entry);
    return 'OK';
  }

  async lrange(key: string, start: number, stop: number): Promise<string[]> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'list') return [];
    const len = entry.value.length;
    const s = start < 0 ? Math.max(0, len + start) : start;
    const e = stop < 0 ? len + stop : stop;
    return entry.value.slice(s, e + 1);
  }

  async llen(key: string): Promise<number> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'list') return 0;
    return entry.value.length;
  }

  async lrem(key: string, count: number, value: string): Promise<number> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'list') return 0;
    // count=0 -> remove all matching values.
    // count>0 -> from head, at most `count` matches.
    // count<0 -> from tail, at most |count| matches.
    let removed = 0;
    if (count === 0) {
      const before = entry.value.length;
      entry.value = entry.value.filter((v) => v !== value);
      removed = before - entry.value.length;
    } else if (count > 0) {
      const out: string[] = [];
      for (const v of entry.value) {
        if (v === value && removed < count) { removed += 1; continue; }
        out.push(v);
      }
      entry.value = out;
    } else {
      const limit = -count;
      const out = entry.value.slice();
      for (let i = out.length - 1; i >= 0 && removed < limit; i--) {
        if (out[i] === value) { out.splice(i, 1); removed += 1; }
      }
      entry.value = out;
    }
    if (entry.value.length === 0) this.store.delete(key);
    return removed;
  }

  async lset(key: string, index: number, value: string): Promise<'OK'> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'list') {
      throw new Error('ERR no such key');
    }
    const len = entry.value.length;
    const idx = index < 0 ? len + index : index;
    if (idx < 0 || idx >= len) {
      throw new Error('ERR index out of range');
    }
    entry.value[idx] = value;
    return 'OK';
  }

  async set(
    key: string,
    value: string,
    mode?: 'EX',
    ttlSeconds?: number,
  ): Promise<'OK'> {
    const expiresAt =
      mode === 'EX' && ttlSeconds !== undefined
        ? Date.now() + ttlSeconds * 1000
        : undefined;
    this.store.set(key, { type: 'string', value, expiresAt });
    return 'OK';
  }

  async get(key: string): Promise<string | null> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'string') return null;
    return entry.value;
  }

  async del(key: string): Promise<number> {
    return this.store.delete(key) ? 1 : 0;
  }

  async sadd(key: string, ...members: string[]): Promise<number> {
    const entry = this.getEntry(key);
    const set = entry && entry.type === 'set' ? entry.value : new Set<string>();
    let added = 0;
    for (const m of members) {
      if (!set.has(m)) {
        set.add(m);
        added += 1;
      }
    }
    this.store.set(key, { type: 'set', value: set, expiresAt: entry?.expiresAt });
    return added;
  }

  async srem(key: string, ...members: string[]): Promise<number> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'set') return 0;
    let removed = 0;
    for (const m of members) {
      if (entry.value.delete(m)) removed += 1;
    }
    if (entry.value.size === 0) {
      this.store.delete(key);
    }
    return removed;
  }

  async smembers(key: string): Promise<string[]> {
    const entry = this.getEntry(key);
    if (!entry || entry.type !== 'set') return [];
    return Array.from(entry.value);
  }
}

// ---------------- Singleton (lazy) ----------------

declare global {
  // eslint-disable-next-line no-var
  var __sendwise_redis__: RedisLike | undefined;
}

function isValidRedisUrl(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'redis:' || u.protocol === 'rediss:';
  } catch {
    return false;
  }
}

function createClient(): RedisLike {
  // Accept either REDIS_URL (standard) or SENDWISE_REDIS_URL (Vercel KV
  // integration convention that prefixes env vars by database name).
  const url =
    process.env.REDIS_URL ||
    process.env.SENDWISE_REDIS_URL ||
    process.env.sendwise_REDIS_URL;
  if (!url) {
    // eslint-disable-next-line no-console
    console.warn(
      '[sendwise] REDIS_URL not set — using in-memory Redis stub (dev only).',
    );
    return new InMemoryRedis();
  }
  if (!isValidRedisUrl(url)) {
    // eslint-disable-next-line no-console
    console.warn(
      '[sendwise] REDIS_URL is malformed (expected redis:// or rediss:// URL) — falling back to in-memory stub. Fix the env var to persist data.',
    );
    return new InMemoryRedis();
  }
  // Lazy-require ioredis so the stub path works even if the dependency
  // hasn't been installed yet.
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const IORedisCtor = require('ioredis') as { default?: new (url: string) => IORedis } & (new (url: string) => IORedis);
    const Ctor = (IORedisCtor as { default?: new (url: string) => IORedis }).default ?? IORedisCtor;
    const client = new (Ctor as new (url: string) => IORedis)(url);
    return client as unknown as RedisLike;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn(
      '[sendwise] Failed to init ioredis client — falling back to in-memory stub. Error:',
      err instanceof Error ? err.message : err,
    );
    return new InMemoryRedis();
  }
}

// Lazy proxy — do NOT construct the client at module load time (breaks Next.js
// build-time page-data collection when REDIS_URL is missing/invalid). The
// client is created on the first actual command invocation.
function getClient(): RedisLike {
  if (!globalThis.__sendwise_redis__) {
    globalThis.__sendwise_redis__ = createClient();
  }
  return globalThis.__sendwise_redis__;
}

export const redis: RedisLike = new Proxy({} as RedisLike, {
  get(_target, prop: string | symbol) {
    const client = getClient() as unknown as Record<string | symbol, unknown>;
    const value = client[prop];
    if (typeof value === 'function') {
      return value.bind(client);
    }
    return value;
  },
});
