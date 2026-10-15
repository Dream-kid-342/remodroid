export type Clock = () => number;

/** Classic token bucket used for per-connection and per-IP message rate limits. */
export class TokenBucket {
  private tokens: number;
  private last: number;

  constructor(
    private readonly capacity: number,
    private readonly refillPerSecond: number,
    private readonly now: Clock = Date.now,
  ) {
    this.tokens = capacity;
    this.last = now();
  }

  take(count = 1): boolean {
    const t = this.now();
    this.tokens = Math.min(this.capacity, this.tokens + ((t - this.last) / 1000) * this.refillPerSecond);
    this.last = t;
    if (this.tokens < count) return false;
    this.tokens -= count;
    return true;
  }
}

/** Keyed collection of token buckets that forgets idle keys. */
export class KeyedRateLimiter {
  private readonly buckets = new Map<string, { bucket: TokenBucket; lastUsed: number }>();

  constructor(
    private readonly capacity: number,
    private readonly refillPerSecond: number,
    private readonly now: Clock = Date.now,
  ) {}

  take(key: string, count = 1): boolean {
    let entry = this.buckets.get(key);
    if (!entry) {
      entry = { bucket: new TokenBucket(this.capacity, this.refillPerSecond, this.now), lastUsed: this.now() };
      this.buckets.set(key, entry);
    }
    entry.lastUsed = this.now();
    return entry.bucket.take(count);
  }

  prune(idleMs: number): void {
    const cutoff = this.now() - idleMs;
    for (const [key, entry] of this.buckets) if (entry.lastUsed < cutoff) this.buckets.delete(key);
  }
}

export interface FailureTrackerOptions {
  maxFailures: number;
  windowMs: number;
  lockoutBaseMs: number;
  lockoutMaxMs: number;
}

interface FailureEntry {
  failures: number;
  windowStart: number;
  lockedUntil: number;
  lockouts: number;
  lastEvent: number;
}

/**
 * Counts authentication failures per key (an IP address or a target device ID).
 * After `maxFailures` failures inside `windowMs`, the key is locked. Each further lockout doubles
 * the lock duration, capped at `lockoutMaxMs`.
 */
export class FailureTracker {
  private readonly entries = new Map<string, FailureEntry>();

  constructor(
    private readonly opts: FailureTrackerOptions,
    private readonly now: Clock = Date.now,
  ) {}

  /** Milliseconds until the key is unlocked, or 0 when it is not locked. */
  lockedFor(key: string): number {
    const entry = this.entries.get(key);
    if (!entry) return 0;
    return Math.max(0, entry.lockedUntil - this.now());
  }

  recordFailure(key: string): void {
    const t = this.now();
    let entry = this.entries.get(key);
    if (!entry) {
      entry = { failures: 0, windowStart: t, lockedUntil: 0, lockouts: 0, lastEvent: t };
      this.entries.set(key, entry);
    }
    if (t - entry.windowStart > this.opts.windowMs) {
      entry.failures = 0;
      entry.windowStart = t;
    }
    entry.failures += 1;
    entry.lastEvent = t;
    if (entry.failures >= this.opts.maxFailures) {
      const duration = Math.min(this.opts.lockoutMaxMs, this.opts.lockoutBaseMs * 2 ** entry.lockouts);
      entry.lockedUntil = t + duration;
      entry.lockouts += 1;
      entry.failures = 0;
      entry.windowStart = t;
    }
  }

  /** A success clears the failure count but keeps the escalation history until it ages out. */
  recordSuccess(key: string): void {
    const entry = this.entries.get(key);
    if (entry) {
      entry.failures = 0;
      entry.lastEvent = this.now();
    }
  }

  prune(): void {
    const t = this.now();
    const forgetAfter = Math.max(this.opts.windowMs, this.opts.lockoutMaxMs) * 2;
    for (const [key, entry] of this.entries) {
      if (entry.lockedUntil <= t && t - entry.lastEvent > forgetAfter) this.entries.delete(key);
    }
  }
}
