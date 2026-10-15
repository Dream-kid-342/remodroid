import { createHash, randomBytes, randomInt, scrypt, timingSafeEqual, type ScryptOptions } from "node:crypto";

/** RemoDroid IDs are 9 decimal digits, first digit non-zero. They carry no device or personal information. */
export const DEVICE_ID_LENGTH = 9;

/**
 * Temporary passwords use an alphabet without look-alike characters (no 0/o, 1/l/i).
 * 31 symbols ^ 8 characters ≈ 39.6 bits. Online guessing is bounded by the lockout policy in
 * rateLimiter.ts and the password's limited lifetime, and host approval is still required.
 */
export const PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
export const PASSWORD_LENGTH = 8;

const SCRYPT_KEYLEN = 32;
const SCRYPT_OPTIONS: ScryptOptions = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };

export interface StoredSecret {
  salt: Buffer;
  hash: Buffer;
}

export function generateDeviceId(): string {
  let id = String(randomInt(1, 10));
  for (let i = 1; i < DEVICE_ID_LENGTH; i++) id += String(randomInt(0, 10));
  return id;
}

export function normalizeDeviceId(input: string): string {
  return input.replace(/[\s-]/g, "");
}

export function isValidDeviceId(id: string): boolean {
  return /^[1-9][0-9]{8}$/.test(id);
}

export function generatePassword(length = PASSWORD_LENGTH): string {
  let out = "";
  for (let i = 0; i < length; i++) out += PASSWORD_ALPHABET[randomInt(0, PASSWORD_ALPHABET.length)];
  return out;
}

/** Passwords are case-insensitive and ignore spaces so they are easy to read aloud and type on a phone. */
export function normalizePassword(input: string): string {
  return input.replace(/\s/g, "").toLowerCase();
}

function scryptAsync(secret: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(secret, salt, SCRYPT_KEYLEN, SCRYPT_OPTIONS, (err, key) => (err ? reject(err) : resolve(key)));
  });
}

export async function hashSecret(secret: string): Promise<StoredSecret> {
  const salt = randomBytes(16);
  return { salt, hash: await scryptAsync(normalizePassword(secret), salt) };
}

export async function verifySecret(candidate: string, stored: StoredSecret): Promise<boolean> {
  const computed = await scryptAsync(normalizePassword(candidate), stored.salt);
  return computed.length === stored.hash.length && timingSafeEqual(computed, stored.hash);
}

let dummySecret: Promise<StoredSecret> | undefined;

/**
 * Burns the same amount of work as a real verification so that "unknown ID" and
 * "wrong password" cannot be distinguished by response timing.
 */
export async function verifyAgainstDummy(candidate: string): Promise<false> {
  dummySecret ??= hashSecret(generatePassword());
  await verifySecret(candidate, await dummySecret);
  return false;
}

/** High-entropy opaque token (256 bits), URL-safe. */
export function generateToken(): string {
  return randomBytes(32).toString("base64url");
}

/** Tokens are high entropy, so a fast hash is sufficient for at-rest protection. */
export function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

/** Short, non-reversible label for logs. Never log raw IDs, passwords or tokens. */
export function logLabel(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 10);
}
