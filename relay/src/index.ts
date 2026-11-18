import { createRemoteJWKSet, importPKCS8, jwtVerify, SignJWT } from 'jose';
import { informationPage } from './privacy';
import { GoogleHealthError, googleWebhook, googleIdentity, googleSubscriptionHealth } from './google-health';

export interface Env {
  DB: D1Database; EVENTS: Queue<Invalidation>;
  FIREBASE_PROJECT_ID: string;
  FITBIT_CLIENT_ID: string; FITBIT_CLIENT_SECRET: string;
  FITBIT_ALLOWED_ACCOUNT: string; FITBIT_VERIFY_CODE: string;
  FITBIT_SUBSCRIBER_ID: string; FITBIT_SUBSCRIPTION_ID: string;
  FCM_SERVICE_ACCOUNT: string;
  GOOGLE_WEBHOOK_AUTH?: string; GOOGLE_ALLOWED_ACCOUNT?: string;
  GOOGLE_SUBSCRIBER_NAME?: string; GOOGLE_SUBSCRIBER_READER?: string;
}
export interface Invalidation { revision: string; receivedAt: number; account: string; provider?: 'fitbit' | 'google_health'; type: 'sleep_invalidated' | 'registration_refresh' }
interface Device { uid: string; account_id: string; fcm_token: string }
class HttpError extends Error { constructor(readonly status: number, readonly category: string) { super(category); } }
const reply = (value: unknown, status = 200) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } });
const jwks = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));

async function firebaseUid(request: Request, env: Env) {
  const token = request.headers.get('Authorization')?.match(/^Bearer (\S+)$/)?.[1];
  if (!token || token.length > 8192) throw new HttpError(401, 'authentication');
  try {
    const { payload } = await jwtVerify(token, jwks, { algorithms: ['RS256'], audience: env.FIREBASE_PROJECT_ID,
      issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}` });
    if (!payload.sub || payload.sub.length > 128 || typeof payload.auth_time !== 'number' || payload.auth_time > Date.now() / 1000 ||
      (payload.firebase as { sign_in_provider?: string } | undefined)?.sign_in_provider !== 'anonymous') throw new Error();
    return payload.sub;
  } catch { throw new HttpError(401, 'authentication'); }
}

export async function verifySignature(raw: ArrayBuffer, signature: string | null, secret: string): Promise<boolean> {
  if (!signature || !secret) return false;
  try {
    const bytes = Uint8Array.from(atob(signature), c => c.charCodeAt(0));
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(`${secret}&`), { name: 'HMAC', hash: 'SHA-1' }, false, ['verify']);
    return await crypto.subtle.verify('HMAC', key, bytes, raw);
  } catch { return false; }
}

export function validateFitbitIdentity(value: Record<string, unknown>, env: Pick<Env, 'FITBIT_ALLOWED_ACCOUNT' | 'FITBIT_CLIENT_ID'>, now = Date.now() / 1000) {
  // Fitbit v1.1 currently returns epoch milliseconds and a Java-map scope string.
  // Accept the RFC-style seconds/space-delimited representation as well.
  const expiry = typeof value.exp === 'number' ? (value.exp > 1e12 ? value.exp / 1000 : value.exp) : 0;
  const scope = typeof value.scope === 'string' ? value.scope : '';
  const sleepAllowed = scope.split(' ').includes('sleep') ||
    /^\{[^{}]*\}$/.test(scope) && scope.slice(1, -1).split(',').some(s => /^SLEEP=READ(?:_WRITE)?$/.test(s.trim()));
  // Never trust identity claims supplied by the phone itself.
  if (value.active !== true || value.client_id !== env.FITBIT_CLIENT_ID || value.user_id !== env.FITBIT_ALLOWED_ACCOUNT ||
    !Number.isFinite(expiry) || expiry <= now + 30 || expiry > now + 8 * 3600 + 120 || !sleepAllowed) throw new HttpError(403, 'fitbit_account');
  return value.user_id as string;
}

async function fitbitIdentity(token: string, env: Env) {
  const response = await fetch('https://api.fitbit.com/1.1/oauth2/introspect', { method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ token }), signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new HttpError(response.status === 429 ? 429 : 503, 'fitbit_validation_unavailable');
  return validateFitbitIdentity(await response.json(), env);
}

export async function ensureSubscription(token: string, env: Env): Promise<string> {
  const headers = { Authorization: `Bearer ${token}`, 'X-Fitbit-Subscriber-Id': env.FITBIT_SUBSCRIBER_ID };
  // Idempotent create checks health without removing any pre-existing subscription.
  try {
    const response = await fetch(`https://api.fitbit.com/1/user/-/sleep/apiSubscriptions/${encodeURIComponent(env.FITBIT_SUBSCRIPTION_ID)}.json`,
      { method: 'POST', headers, body: '', signal: AbortSignal.timeout(10_000) });
    await response.arrayBuffer();
    if (response.status === 200 || response.status === 201) return 'active';
    if (response.status === 409) return 'conflict';
    return `unavailable_${response.status}`;
  } catch { return 'unavailable'; }
}

async function enrollment(request: Request, env: Env, uid: string) {
  const body = await request.text();
  if (body.length > 16_384) throw new HttpError(413, 'too_large');
  let input: { provider?: string; googleToken?: string; fitbitToken?: string; fcmToken?: string };
  try { input = JSON.parse(body); } catch { throw new HttpError(400, 'invalid_json'); }
  const provider = input.provider ?? 'fitbit';
  if (provider !== 'fitbit' && provider !== 'google_health') throw new HttpError(400, 'invalid_provider');
  const token = provider === 'google_health' ? input.googleToken : input.fitbitToken;
  if (typeof token !== 'string' || !token || token.length > 4096 || typeof input.fcmToken !== 'string' ||
    input.fcmToken.length < 20 || input.fcmToken.length > 4096) throw new HttpError(400, 'invalid_registration');
  const account = provider === 'google_health' ? await googleIdentity(token, env) : await fitbitIdentity(token, env);
  const subscription = provider === 'google_health' ? await googleSubscriptionHealth(env) : await ensureSubscription(token, env);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM devices WHERE fcm_token = ? AND uid != ?').bind(input.fcmToken, uid),
    env.DB.prepare(`INSERT INTO devices(uid, account_id, fcm_token, subscription_status, registered_at, provider) VALUES(?,?,?,?,?,?)
      ON CONFLICT(uid) DO UPDATE SET account_id=excluded.account_id, fcm_token=excluded.fcm_token, provider=excluded.provider,
      subscription_status=excluded.subscription_status, registered_at=excluded.registered_at, last_error=NULL`)
      .bind(uid, account, input.fcmToken, subscription, Date.now(), provider)
  ]);
  return reply({ subscription });
}

export async function webhook(request: Request, env: Env): Promise<Response> {
  if (request.method === 'GET') {
    const valid = env.FITBIT_VERIFY_CODE && new URL(request.url).searchParams.get('verify') === env.FITBIT_VERIFY_CODE;
    return new Response(null, { status: valid ? 204 : 404 });
  }
  if (request.method !== 'POST') return new Response(null, { status: 405 });
  if (Number(request.headers.get('Content-Length') || 0) > 65_536) return new Response(null, { status: 413 });
  const raw = await request.arrayBuffer();
  if (raw.byteLength > 65_536) return new Response(null, { status: 413 });
  if (!await verifySignature(raw, request.headers.get('X-Fitbit-Signature'), env.FITBIT_CLIENT_SECRET)) return new Response(null, { status: 401 });
  let events: { ownerId?: string; collectionType?: string; subscriptionId?: string }[];
  try { events = JSON.parse(new TextDecoder().decode(raw)); } catch { return new Response(null, { status: 400 }); }
  if (!Array.isArray(events) || events.length > 100) return new Response(null, { status: 400 });
  const relevant = events.some(e => e && e.ownerId === env.FITBIT_ALLOWED_ACCOUNT && e.collectionType === 'sleep' && e.subscriptionId === env.FITBIT_SUBSCRIPTION_ID);
  if (relevant) {
    // Unique per delivery, not a hash of Fitbit's date-only payload: successive naps can look identical.
    await env.EVENTS.send({ revision: crypto.randomUUID(), receivedAt: Date.now(), account: env.FITBIT_ALLOWED_ACCOUNT, type: 'sleep_invalidated' });
  }
  // Queue acknowledgement is awaited before accepting. An outage never produces a false 204.
  return new Response(null, { status: 204 });
}

let cachedAccess: { token: string; expires: number; project: string } | undefined;
async function fcmAccess(env: Env): Promise<string> {
  if (cachedAccess && cachedAccess.project === env.FIREBASE_PROJECT_ID && cachedAccess.expires > Date.now() + 60_000) return cachedAccess.token;
  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT) as { project_id: string; client_email: string; private_key: string };
  if (account.project_id !== env.FIREBASE_PROJECT_ID) throw new Error('fcm_configuration');
  const key = await importPKCS8(account.private_key, 'RS256');
  const jwt = await new SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' }).setProtectedHeader({ alg: 'RS256' })
    .setIssuer(account.client_email).setAudience('https://oauth2.googleapis.com/token').setIssuedAt().setExpirationTime('1h').sign(key);
  const response = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({
    grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }), signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error('fcm_authorization');
  const value = await response.json<{ access_token: string; expires_in: number }>();
  cachedAccess = { token: value.access_token, expires: Date.now() + value.expires_in * 1000, project: env.FIREBASE_PROJECT_ID };
  return value.access_token;
}

export function pushMessage(token: string, event: Invalidation) {
  return { message: { token, data: { type: event.type, revision: event.revision, provider: event.provider ?? 'fitbit' },
    android: { priority: 'normal', collapse_key: event.type, ttl: '86400s' } } };
}
export function invalidRegistration(status: number, body: { error?: { details?: { errorCode?: string }[] } }) {
  // Generic INVALID_ARGUMENT may be a payload error; never delete a device based on that alone.
  return status === 404 && body.error?.details?.some(e => e.errorCode === 'UNREGISTERED') === true;
}

async function deliver(event: Invalidation, env: Env) {
  const devices = await env.DB.prepare('SELECT uid, account_id, fcm_token FROM devices WHERE account_id = ? AND provider = ?').bind(event.account, event.provider ?? 'fitbit').all<Device>();
  let retry = false;
  for (const device of devices.results) {
    const prior = await env.DB.prepare('SELECT sent_at FROM deliveries WHERE revision = ? AND uid = ?').bind(event.revision, device.uid).first<{ sent_at: number | null }>();
    if (prior?.sent_at) continue;
    await env.DB.prepare('INSERT OR IGNORE INTO deliveries(revision,uid,created_at) VALUES(?,?,?)').bind(event.revision, device.uid, event.receivedAt).run();
    try {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
        method: 'POST', headers: { Authorization: `Bearer ${await fcmAccess(env)}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(pushMessage(device.fcm_token, event)), signal: AbortSignal.timeout(15_000)
      });
      const body = await response.json<{ error?: { details?: { errorCode?: string }[] } }>();
      if (invalidRegistration(response.status, body)) {
        await env.DB.prepare('DELETE FROM devices WHERE uid = ? AND fcm_token = ?').bind(device.uid, device.fcm_token).run();
      } else if (response.ok) {
        await env.DB.batch([
          env.DB.prepare('UPDATE deliveries SET sent_at = ?, error = NULL WHERE revision = ? AND uid = ?').bind(Date.now(), event.revision, device.uid),
          env.DB.prepare('UPDATE devices SET last_delivery_at = ?, last_revision = ?, last_error = NULL WHERE uid = ?').bind(Date.now(), event.revision, device.uid)
        ]);
      } else {
        if (response.status === 401) cachedAccess = undefined;
        throw new Error(`fcm_${response.status}`);
      }
    } catch (error) {
      retry = true;
      const category = error instanceof Error && /^fcm_\d+$/.test(error.message) ? error.message : 'delivery_unavailable';
      await env.DB.batch([
        env.DB.prepare('UPDATE deliveries SET error = ? WHERE revision = ? AND uid = ?').bind(category, event.revision, device.uid),
        env.DB.prepare('UPDATE devices SET last_error = ? WHERE uid = ?').bind(category, device.uid)
      ]);
    }
  }
  if (retry) throw new Error('delivery_retry');
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const path = new URL(request.url).pathname;
      if (request.method === 'GET') { const page = informationPage(path); if (page) return page; }
      if (path === '/google-health/webhook') return await googleWebhook(request, env);
      if (path === '/fitbit/webhook') return await webhook(request, env);
      if (path === '/health' && request.method === 'GET') return reply({ service: 'clock-sleep-relay' });
      if (path !== '/devices' && path !== '/status') return reply({ error: 'not_found' }, 404);
      const uid = await firebaseUid(request, env);
      if (path === '/devices' && request.method === 'POST') return await enrollment(request, env, uid);
      if (path === '/devices' && request.method === 'DELETE') {
        await env.DB.prepare('DELETE FROM devices WHERE uid = ?').bind(uid).run(); return reply({ removed: true });
      }
      if (path === '/status' && request.method === 'GET') {
        const device = await env.DB.prepare('SELECT provider, subscription_status, registered_at, last_delivery_at, last_error FROM devices WHERE uid = ?').bind(uid).first();
        return reply({ device: device ?? null });
      }
      return reply({ error: 'method' }, 405);
    } catch (error) {
      const known = error instanceof HttpError || error instanceof GoogleHealthError;
      return reply({ error: known ? error.category : 'unavailable', pollingFallback: true }, known ? error.status : 503);
    }
  },
  async queue(batch: MessageBatch<Invalidation>, env: Env) {
    for (const message of batch.messages) {
      try { await deliver(message.body, env); message.ack(); }
      catch { message.retry({ delaySeconds: Math.min(3600, 30 * 2 ** Math.min(message.attempts, 7)) }); }
    }
  },
  async scheduled(_event: ScheduledController, env: Env) {
    // Phones hold Fitbit refresh tokens. Ask each phone to validate its subscription daily using a fresh short-lived token.
    await env.EVENTS.send({ revision: crypto.randomUUID(), receivedAt: Date.now(), account: env.FITBIT_ALLOWED_ACCOUNT, type: 'registration_refresh' });
    if (env.GOOGLE_ALLOWED_ACCOUNT) {
      const status = await googleSubscriptionHealth(env);
      await env.DB.prepare("UPDATE devices SET subscription_status = ? WHERE provider = 'google_health'").bind(status).run();
      await env.EVENTS.send({ revision: crypto.randomUUID(), receivedAt: Date.now(), account: env.GOOGLE_ALLOWED_ACCOUNT, provider: 'google_health', type: 'registration_refresh' });
    }
    await env.DB.prepare('DELETE FROM deliveries WHERE created_at < ?').bind(Date.now() - 7 * 86_400_000).run();
  }
} satisfies ExportedHandler<Env, Invalidation>;
