import { importPKCS8, SignJWT } from 'jose';
import type { Env, Invalidation } from './index';

const KEYSET_URL = 'https://www.gstatic.com/googlehealthapi/webhooks/webhooks_public_keyset.json';
export interface PublicKeyset { key: { keyId: number; status: string; outputPrefixType: string; keyData: { typeUrl: string; value: string; keyMaterialType: string } }[] }
export class GoogleHealthError extends Error {
  constructor(readonly status: number, readonly category: string) { super(category); }
}
const bytes = (value: string) => Uint8Array.from(atob(value), c => c.charCodeAt(0));
const base64url = (value: Uint8Array) => btoa(String.fromCharCode(...value)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');

/** Minimal bounded protobuf decoder for the documented public ECDSA key envelope. */
function proto(raw: Uint8Array): Map<number, number | Uint8Array> {
  let index = 0;
  const read = () => {
    let result = 0, shift = 0;
    while (index < raw.length && shift < 35) {
      const next = raw[index++]; result += (next & 127) * 2 ** shift;
      if (!(next & 128)) return result;
      shift += 7;
    }
    throw new Error('protobuf');
  };
  const fields = new Map<number, number | Uint8Array>();
  while (index < raw.length) {
    const tag = read(), field = Math.floor(tag / 8), wire = tag & 7;
    if (!field || fields.has(field)) throw new Error('protobuf');
    if (wire === 0) fields.set(field, read());
    else if (wire === 2) {
      const size = read();
      if (size > raw.length - index) throw new Error('protobuf');
      fields.set(field, raw.slice(index, index += size));
    } else throw new Error('protobuf');
  }
  return fields;
}
function coordinate(raw: unknown): Uint8Array {
  if (!(raw instanceof Uint8Array)) throw new Error('coordinate');
  if (raw.length === 33 && raw[0] === 0) raw = raw.slice(1);
  if ((raw as Uint8Array).length !== 32) throw new Error('coordinate');
  return raw as Uint8Array;
}
export function derToRaw(der: Uint8Array): Uint8Array {
  if (der.length < 8 || der.length > 72 || der[0] !== 48 || der[1] !== der.length - 2) throw new Error('signature');
  let index = 2;
  function integer() {
    if (der[index++] !== 2) throw new Error('signature');
    const length = der[index++];
    let value = der.slice(index, index += length);
    if (length < 1 || length > 33 || value.length !== length || (value[0] & 128)) throw new Error('signature');
    if (value[0] === 0 && value.length > 1) {
      if (!(value[1] & 128)) throw new Error('signature');
      value = value.slice(1);
    }
    if (value.length > 32) throw new Error('signature');
    const padded = new Uint8Array(32); padded.set(value, 32 - value.length); return padded;
  }
  const output = new Uint8Array(64); output.set(integer()); output.set(integer(), 32);
  if (index !== der.length) throw new Error('signature');
  return output;
}

let cachedKeys: { value: PublicKeyset; at: number } | undefined;
async function keyset(force = false): Promise<PublicKeyset> {
  if (!force && cachedKeys && Date.now() - cachedKeys.at < 86_400_000) return cachedKeys.value;
  try {
    const response = await fetch(KEYSET_URL, { signal: AbortSignal.timeout(2500) });
    if (!response.ok) throw new Error();
    const value = await response.json<PublicKeyset>();
    if (!Array.isArray(value.key) || value.key.length > 100) throw new Error();
    cachedKeys = { value, at: Date.now() }; return value;
  } catch {
    if (!force && cachedKeys && Date.now() - cachedKeys.at < 2 * 86_400_000) return cachedKeys.value;
    throw new GoogleHealthError(503, 'signing_keys_unavailable');
  }
}

export async function verifyGoogleSignature(raw: ArrayBuffer, signature: string | null,
  load: (force?: boolean) => Promise<PublicKeyset> = keyset): Promise<boolean> {
  if (!signature || signature.length > 256) return false;
  let envelope: Uint8Array;
  try { envelope = bytes(signature); } catch { return false; }
  if (envelope.length < 13 || envelope[0] !== 1) return false;
  const id = new DataView(envelope.buffer).getUint32(1, false);
  let keys = await load();
  if (!keys.key.some(k => k.keyId === id)) keys = await load(true);
  const selected = keys.key.find(k => k.keyId === id && k.status === 'ENABLED' && k.outputPrefixType === 'TINK');
  if (!selected || selected.keyData.typeUrl !== 'type.googleapis.com/google.crypto.tink.EcdsaPublicKey' || selected.keyData.keyMaterialType !== 'ASYMMETRIC_PUBLIC') return false;
  try {
    const fields = proto(bytes(selected.keyData.value));
    if ((fields.get(1) ?? 0) !== 0 || !(fields.get(2) instanceof Uint8Array)) return false;
    const params = proto(fields.get(2) as Uint8Array);
    if (params.get(1) !== 3 || params.get(2) !== 2 || params.get(3) !== 2) return false; // SHA256 / P256 / DER
    const key = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256',
      x: base64url(coordinate(fields.get(3))), y: base64url(coordinate(fields.get(4))), ext: true },
      { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, derToRaw(envelope.slice(5)), raw);
  } catch { return false; }
}

export function googleEvents(input: unknown, allowed: string): Invalidation[] {
  const events = Array.isArray(input) ? input : [input];
  if (events.length > 99 || events.some(e => !e || typeof e !== 'object' || !e.data)) throw new GoogleHealthError(400, 'invalid_events');
  return events.filter(e => e.data.healthUserId === allowed && e.data.dataType === 'sleep' &&
    (e.data.operation === 'UPSERT' || e.data.operation === 'DELETE')).map(() => ({
      revision: crypto.randomUUID(), receivedAt: Date.now(), account: allowed, provider: 'google_health', type: 'sleep_invalidated'
    }));
}

export async function googleWebhook(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'POST') return new Response(null, { status: 405 });
  if (!env.GOOGLE_WEBHOOK_AUTH) throw new GoogleHealthError(503, 'google_not_configured');
  if (request.headers.get('Authorization') !== env.GOOGLE_WEBHOOK_AUTH) return new Response(null, { status: 401 });
  if (Number(request.headers.get('Content-Length') || 0) > 262144) return new Response(null, { status: 413 });
  const raw = await request.arrayBuffer();
  if (raw.byteLength > 262144) return new Response(null, { status: 413 });
  let input: unknown;
  try { input = JSON.parse(new TextDecoder().decode(raw)); } catch { return new Response(null, { status: 400 }); }
  // Google's setup challenge is authorized but unsigned and must return 200.
  if (input && typeof input === 'object' && !Array.isArray(input) &&
      Object.keys(input).length === 1 && (input as { type?: string }).type === 'verification') return new Response(null, { status: 200 });
  if (!env.GOOGLE_ALLOWED_ACCOUNT) throw new GoogleHealthError(503, 'google_not_configured');
  if (!await verifyGoogleSignature(raw, request.headers.get('GOOGLE-HEALTH-API-SIGNATURE'))) return new Response(null, { status: 401 });
  const events = googleEvents(input, env.GOOGLE_ALLOWED_ACCOUNT);
  if (events.length) await env.EVENTS.sendBatch(events.map(body => ({ body })));
  return new Response(null, { status: 204 });
}

export async function googleIdentity(token: string, env: Env): Promise<string> {
  const response = await fetch('https://health.googleapis.com/v4/users/me/identity', {
    headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(10_000)
  });
  if (!response.ok) throw new GoogleHealthError(response.status === 401 ? 401 : 503, 'google_validation_unavailable');
  const value = await response.json<{ healthUserId?: string; legacyUserId?: string }>();
  if (!env.GOOGLE_ALLOWED_ACCOUNT || value.healthUserId !== env.GOOGLE_ALLOWED_ACCOUNT || value.legacyUserId !== env.FITBIT_ALLOWED_ACCOUNT)
    throw new GoogleHealthError(403, 'google_account');
  return value.healthUserId;
}

/** Dedicated read-only service identity, separate from the FCM sender and subscriber administrator. */
export async function googleSubscriptionHealth(env: Env): Promise<string> {
  if (!env.GOOGLE_SUBSCRIBER_READER || !env.GOOGLE_SUBSCRIBER_NAME) return 'not_configured';
  try {
    const service = JSON.parse(env.GOOGLE_SUBSCRIBER_READER);
    if (service.project_id !== env.FIREBASE_PROJECT_ID) return 'configuration';
    const key = await importPKCS8(service.private_key, 'RS256');
    const assertion = await new SignJWT({ scope: 'https://www.googleapis.com/auth/cloud-platform' })
      .setProtectedHeader({ alg: 'RS256' }).setIssuer(service.client_email)
      .setAudience('https://oauth2.googleapis.com/token').setIssuedAt().setExpirationTime('5m').sign(key);
    const auth = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }), signal: AbortSignal.timeout(5000) });
    if (!auth.ok) return 'authorization_unavailable';
    const token = await auth.json<{ access_token: string }>();
    const response = await fetch(`https://health.googleapis.com/v4/${env.GOOGLE_SUBSCRIBER_NAME.split('/subscribers/')[0]}/subscribers?pageSize=100`, {
      headers: { Authorization: `Bearer ${token.access_token}` }, signal: AbortSignal.timeout(5000) });
    if (!response.ok) return `unavailable_${response.status}`;
    const listing = await response.json<{ subscribers?: { name?: string; endpointUri?: string; endpointAuthorization?: { authorizationTokenSet?: boolean; secretSet?: boolean };
      subscriberConfigs?: { dataTypes?: string[]; subscriptionCreatePolicy?: string }[] }[] }>();
    const subscriber = listing.subscribers?.find(s => s.name === env.GOOGLE_SUBSCRIBER_NAME);
    return subscriber?.endpointUri === 'https://clock-sleep-relay.clock-sleep-personal.workers.dev/google-health/webhook' &&
      (subscriber.endpointAuthorization?.secretSet === true || subscriber.endpointAuthorization?.authorizationTokenSet === true) && subscriber.subscriberConfigs?.some(c =>
        c.subscriptionCreatePolicy === 'AUTOMATIC' && c.dataTypes?.includes('sleep')) ? 'active' : 'configuration';
  } catch { return 'unavailable'; }
}
