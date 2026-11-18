import { beforeEach, expect, it, vi } from 'vitest';
import worker, { type Env } from '../src/index';
import { derToRaw, googleEvents, googleIdentity, googleSubscriptionHealth, verifyGoogleSignature, type PublicKeyset } from '../src/google-health';
import { generateKeyPair, exportPKCS8 } from 'jose';

const b64 = (v: Uint8Array) => btoa(String.fromCharCode(...v));
const unb64 = (v: string) => Uint8Array.from(atob(v.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
const raw = new TextEncoder().encode(JSON.stringify({ data: { healthUserId: 'owner', dataType: 'sleep', operation: 'UPSERT' } }));
function der(raw: Uint8Array) {
  const integer = (value: Uint8Array) => {
    while (value.length > 1 && value[0] === 0) value = value.slice(1);
    if (value[0] & 128) value = new Uint8Array([0, ...value]);
    return [2, value.length, ...value];
  };
  const values = [...integer(raw.slice(0, 32)), ...integer(raw.slice(32))];
  return new Uint8Array([48, values.length, ...values]);
}
async function fixture(id = 123) {
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const jwk = await crypto.subtle.exportKey('jwk', keys.publicKey) as JsonWebKey;
  const x = unb64(jwk.x!), y = unb64(jwk.y!);
  const value = b64(new Uint8Array([18, 6, 8, 3, 16, 2, 24, 2, 26, 33, 0, ...x, 34, 33, 0, ...y]));
  const keyset: PublicKeyset = { key: [{ keyId: id, status: 'ENABLED', outputPrefixType: 'TINK', keyData: {
    typeUrl: 'type.googleapis.com/google.crypto.tink.EcdsaPublicKey', value, keyMaterialType: 'ASYMMETRIC_PUBLIC' } }] };
  const sign = async (body = raw) => {
    const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, keys.privateKey, body));
    const prefix = new Uint8Array(5); prefix[0] = 1; new DataView(prefix.buffer).setUint32(1, id, false);
    return b64(new Uint8Array([...prefix, ...der(signature)]));
  };
  return { keyset, sign };
}
beforeEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('verifies the Tink protobuf envelope and DER signature over exact raw bytes', async () => {
  const f = await fixture(); const signature = await f.sign();
  expect(await verifyGoogleSignature(raw.buffer as ArrayBuffer, signature, async () => f.keyset)).toBe(true);
  expect(await verifyGoogleSignature(new TextEncoder().encode('tampered').buffer as ArrayBuffer, signature, async () => f.keyset)).toBe(false);
  f.keyset.key[0].status = 'DISABLED';
  expect(await verifyGoogleSignature(raw.buffer as ArrayBuffer, signature, async () => f.keyset)).toBe(false);
});
it('refreshes an unknown key and fails closed on key-service outage', async () => {
  const f = await fixture(456); const load = vi.fn(async (force?: boolean) => force ? f.keyset : { key: [] });
  expect(await verifyGoogleSignature(raw.buffer as ArrayBuffer, await f.sign(), load)).toBe(true);
  expect(load.mock.calls).toEqual([[], [true]]);
  await expect(verifyGoogleSignature(raw.buffer as ArrayBuffer, await f.sign(), async () => { throw new Error('outage'); })).rejects.toThrow();
});
it('rejects malformed or non-minimal DER integers', () => {
  for (const value of [[], [48, 6, 2, 1, 128, 2, 1, 1], [48, 7, 2, 2, 0, 1, 2, 1, 1]])
    expect(() => derToRaw(new Uint8Array(value))).toThrow();
});
it('handles batches, deletions and successive identical naps without leaking sleep details', () => {
  const event = JSON.parse(new TextDecoder().decode(raw));
  const events = googleEvents([event, event, { data: { ...event.data, operation: 'DELETE' } },
    { data: { ...event.data, healthUserId: 'other' } }], 'owner');
  expect(events).toHaveLength(3); expect(new Set(events.map(e => e.revision)).size).toBe(3);
  expect(events.every(e => e.provider === 'google_health' && !('intervals' in e))).toBe(true);
});
it('implements the authenticated setup handshake without accepting unsigned health events', async () => {
  const env = { GOOGLE_WEBHOOK_AUTH: 'Bearer test', GOOGLE_ALLOWED_ACCOUNT: 'owner' } as Env;
  const request = (body: string, auth = true) => new Request('https://relay/google-health/webhook', {
    method: 'POST', body, headers: auth ? { Authorization: 'Bearer test' } : {} });
  expect((await worker.fetch(request('{"type":"verification"}'), env)).status).toBe(200);
  expect((await worker.fetch(request('{"type":"verification"}', false), env)).status).toBe(401);
  expect((await worker.fetch(request(new TextDecoder().decode(raw)), env)).status).toBe(401);
});
it('awaits queue acceptance and reports queue quota failures for signed events', async () => {
  const f = await fixture(); vi.stubGlobal('fetch', vi.fn(async () => Response.json(f.keyset)));
  const sendBatch = vi.fn(async () => {});
  const env = { GOOGLE_WEBHOOK_AUTH: 'Bearer test', GOOGLE_ALLOWED_ACCOUNT: 'owner', EVENTS: { sendBatch } } as unknown as Env;
  const request = async () => new Request('https://relay/google-health/webhook', { method: 'POST', body: raw,
    headers: { Authorization: 'Bearer test', 'GOOGLE-HEALTH-API-SIGNATURE': await f.sign() } });
  expect((await worker.fetch(await request(), env)).status).toBe(204);
  expect(sendBatch).toHaveBeenCalledTimes(1);
  sendBatch.mockRejectedValue(new Error('quota'));
  expect((await worker.fetch(await request(), env)).status).toBe(503);
});
it('binds both Google and legacy identity and keeps outages distinct from wrong accounts', async () => {
  const env = { GOOGLE_ALLOWED_ACCOUNT: 'owner', FITBIT_ALLOWED_ACCOUNT: 'legacy' } as Env;
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ healthUserId: 'owner', legacyUserId: 'legacy' })));
  expect(await googleIdentity('transient', env)).toBe('owner');
  await expect(googleIdentity('transient', { ...env, FITBIT_ALLOWED_ACCOUNT: 'other' })).rejects.toMatchObject({ status: 403 });
  vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })));
  await expect(googleIdentity('transient', env)).rejects.toMatchObject({ status: 503 });
});
it('checks automatic subscription health using the live secretSet field and separate reader', async () => {
  const { privateKey } = await generateKeyPair('RS256', { extractable: true });
  const env = { FIREBASE_PROJECT_ID: 'test', GOOGLE_SUBSCRIBER_NAME: 'projects/123/subscribers/sleep',
    GOOGLE_SUBSCRIBER_READER: JSON.stringify({ project_id: 'test', client_email: 'reader@example.test', private_key: await exportPKCS8(privateKey) }) } as Env;
  const upstream = vi.fn(async (url: string) => url.includes('oauth2.googleapis.com') ? Response.json({ access_token: 'reader-token' }) :
    Response.json({ subscribers: [{ name: env.GOOGLE_SUBSCRIBER_NAME,
      endpointUri: 'https://clock-sleep-relay.clock-sleep-personal.workers.dev/google-health/webhook',
      endpointAuthorization: { secretSet: true }, subscriberConfigs: [{ dataTypes: ['sleep'], subscriptionCreatePolicy: 'AUTOMATIC' }] }] }));
  vi.stubGlobal('fetch', upstream);
  expect(await googleSubscriptionHealth(env)).toBe('active');
  expect(upstream.mock.calls[1][0]).toBe('https://health.googleapis.com/v4/projects/123/subscribers?pageSize=100');
});
