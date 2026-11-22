import { beforeEach, describe, expect, it, vi } from 'vitest';
import { generateKeyPair, exportPKCS8, jwtVerify } from 'jose';
import worker, { verifySignature, validateFitbitIdentity, pushMessage, invalidRegistration, ensureSubscription, type Env, type Invalidation } from '../src/index';

vi.mock('jose', async importOriginal => ({ ...await importOriginal<typeof import('jose')>(), jwtVerify: vi.fn() }));
const mockedVerify = vi.mocked(jwtVerify);
const event: Invalidation = { revision: 'revision', account: 'allowed', receivedAt: 1000, type: 'sleep_invalidated' };
const rawBody = '[{"ownerId":"allowed","collectionType":"sleep","subscriptionId":"clock"}]';

function environment() {
  const queries: { sql: string; values: unknown[] }[] = [];
  const devices: unknown[] = [];
  const first = vi.fn(async () => null as unknown);
  const run = vi.fn(async () => ({}));
  const DB = {
    prepare(sql: string) {
      const values: unknown[] = []; const query = { sql, values }; queries.push(query);
      const stmt = { bind(...v: unknown[]) { values.push(...v); return stmt; }, run, first,
        all: vi.fn(async () => ({ results: devices })) };
      return stmt;
    }, batch: vi.fn(async () => [])
  };
  return { env: { DB, EVENTS: { send: vi.fn(async () => {}) }, FITBIT_CLIENT_SECRET: 'secret', FITBIT_VERIFY_CODE: 'verify',
    FITBIT_ALLOWED_ACCOUNT: 'allowed', FITBIT_CLIENT_ID: 'client', FITBIT_SUBSCRIBER_ID: 'subscriber', FITBIT_SUBSCRIPTION_ID: 'clock',
    FIREBASE_PROJECT_ID: 'test-project', FCM_SERVICE_ACCOUNT: '' } as unknown as Env, queries, devices, first, run };
}
async function signed(body = rawBody) {
  const bytes = new TextEncoder().encode(body);
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode('secret&'), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
  const value = await crypto.subtle.sign('HMAC', key, bytes);
  return new Request('https://relay/fitbit/webhook', { method: 'POST', body, headers: { 'X-Fitbit-Signature': btoa(String.fromCharCode(...new Uint8Array(value))) } });
}
function validIdentity() { return { active: true, user_id: 'allowed', client_id: 'client', scope: 'sleep heartrate activity', exp: Math.floor(Date.now() / 1000) + 3600 }; }
function registration(token = 'device-token-with-sufficient-length') {
  return new Request('https://relay/devices', { method: 'POST', headers: { Authorization: 'Bearer firebase-id-token' },
    body: JSON.stringify({ fitbitToken: 'transient', fcmToken: token }) });
}
const context = {} as ExecutionContext;

beforeEach(() => {
  vi.restoreAllMocks(); vi.unstubAllGlobals(); mockedVerify.mockReset();
  mockedVerify.mockResolvedValue({ payload: { sub: 'uid', auth_time: Date.now() / 1000 - 10, firebase: { sign_in_provider: 'anonymous' } }, protectedHeader: { alg: 'RS256' } } as never);
});

describe('webhook acceptance', () => {
  it('verifies the exact raw bytes and rejects tampering', async () => {
    const req = await signed(); const raw = await req.arrayBuffer();
    expect(await verifySignature(raw, req.headers.get('X-Fitbit-Signature'), 'secret')).toBe(true);
    expect(await verifySignature(new TextEncoder().encode(rawBody + ' ').buffer as ArrayBuffer, req.headers.get('X-Fitbit-Signature'), 'secret')).toBe(false);
    expect(await verifySignature(raw, null, 'secret')).toBe(false);
  });
  it('queues before 204 and never deduplicates successive same-day naps by body', async () => {
    const { env } = environment();
    expect((await worker.fetch(await signed(), env)).status).toBe(204);
    expect((await worker.fetch(await signed(), env)).status).toBe(204);
    const send = vi.mocked(env.EVENTS.send);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[0][0].revision).not.toBe(send.mock.calls[1][0].revision);
    expect(send.mock.calls[0][0]).not.toHaveProperty('date');
  });
  it('rejects bad verification codes and foreign accounts', async () => {
    const { env } = environment();
    expect((await worker.fetch(new Request('https://relay/fitbit/webhook?verify=verify'), env)).status).toBe(204);
    expect((await worker.fetch(new Request('https://relay/fitbit/webhook?verify=bad'), env)).status).toBe(404);
    await worker.fetch(await signed(rawBody.replace('allowed', 'other')), env);
    expect(env.EVENTS.send).not.toHaveBeenCalled();
  });
  it('reports queue outages and quota exhaustion instead of false acceptance', async () => {
    const { env } = environment(); vi.mocked(env.EVENTS.send).mockRejectedValue(new Error('quota'));
    const response = await worker.fetch(await signed(), env);
    expect(response.status).toBe(503); expect(await response.json()).toMatchObject({ pollingFallback: true });
  });
});

describe('device enrollment', () => {
  it('binds only the configured account and app with a short-lived sleep token', () => {
    const { env } = environment(); const identity = validIdentity(); expect(validateFitbitIdentity(identity, env)).toBe('allowed');
    for (const change of [{ active: false }, { user_id: 'other' }, { client_id: 'other' }, { scope: 'activity' }, { exp: 1 }, { exp: Date.now() / 1000 + 86400 }]) {
      expect(() => validateFitbitIdentity({ ...identity, ...change }, env)).toThrow();
    }
    const liveShape = { ...identity, scope: '{ACTIVITY=READ, HEARTRATE=READ, SLEEP=READ}', exp: Date.now() + 3600_000 };
    expect(validateFitbitIdentity(liveShape, env)).toBe('allowed');
    for (const change of [{ scope: '{ACTIVITY=READ, SLEEP=WRITE}' }, { scope: '{NOT_SLEEP=READ}' },
      { scope: 'SLEEP=READ' }, { exp: Date.now() + 86400_000 }, { exp: NaN }, { user_id: 'other' }]) {
      expect(() => validateFitbitIdentity({ ...liveShape, ...change }, env)).toThrow();
    }
  });
  it('requires Firebase identity and exact project/issuer validation', async () => {
    const { env } = environment();
    const noAuth = await worker.fetch(new Request('https://relay/status'), env); expect(noAuth.status).toBe(401);
    await worker.fetch(new Request('https://relay/status', { headers: { Authorization: 'Bearer token' } }), env);
    expect(mockedVerify).toHaveBeenCalledWith('token', expect.anything(), { algorithms: ['RS256'], audience: env.FIREBASE_PROJECT_ID, issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}` });
    mockedVerify.mockRejectedValue(new Error('invalid'));
    expect((await worker.fetch(registration(), env)).status).toBe(401);
  });
  it('updates token rotation for one installation and never stores Fitbit tokens', async () => {
    const { env, queries } = environment();
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('introspect') ? Response.json(validIdentity()) : Response.json({}, { status: 201 })));
    expect((await worker.fetch(registration('old-device-token-with-sufficient-length'), env)).status).toBe(200);
    expect((await worker.fetch(registration('rotated-device-token-with-sufficient-length'), env)).status).toBe(200);
    expect(queries.some(q => q.sql.includes('ON CONFLICT(uid) DO UPDATE'))).toBe(true);
    expect(queries.flatMap(q => q.values)).not.toContain('transient');
    expect(queries.flatMap(q => q.values)).toContain('rotated-device-token-with-sufficient-length');
  });
  it('reports subscription conflicts and database quota failures', async () => {
    const { env } = environment(); const upstream = vi.fn(async () => new Response('{}', { status: 409 })); vi.stubGlobal('fetch', upstream);
    expect(await ensureSubscription('token', env)).toBe('conflict'); expect(upstream).toHaveBeenCalledTimes(1);
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('introspect') ? Response.json(validIdentity()) : Response.json({}, { status: 200 })));
    vi.mocked(env.DB.batch).mockRejectedValue(new Error('quota'));
    expect((await worker.fetch(registration(), env)).status).toBe(503);
  });
});

describe('silent delivery and recovery', () => {
  it('never routes legacy events to Google registrations', async () => {
    const { env, queries } = environment();
    const message = { body: event, attempts: 1, ack: vi.fn(), retry: vi.fn() };
    await worker.queue({ messages: [message] } as unknown as MessageBatch<Invalidation>, env);
    const lookup = queries.find(q => q.sql.startsWith('SELECT uid'))!;
    expect(lookup.sql).toContain('account_id = ? AND provider = ?');
    expect(lookup.values).toEqual(['allowed', 'fitbit']);
  });
  it('contains only an invalidation and revision, with normal priority and collapse key', () => {
    const payload = pushMessage('token', event);
    expect(payload.message.data).toEqual({ type: 'sleep_invalidated', revision: 'revision', provider: 'fitbit' });
    expect(payload.message.android.priority).toBe('normal'); expect(payload.message).not.toHaveProperty('notification');
  });
  it('deletes only explicitly unregistered FCM tokens', () => {
    expect(invalidRegistration(404, { error: { details: [{ errorCode: 'UNREGISTERED' }] } })).toBe(true);
    expect(invalidRegistration(400, { error: { details: [{ errorCode: 'INVALID_ARGUMENT' }] } })).toBe(false);
    expect(invalidRegistration(404, {})).toBe(false);
  });
  it('retries infrastructure failures and acknowledges already delivered revisions', async () => {
    const { env, devices, first } = environment(); devices.push({ uid: 'uid', fcm_token: 'token', account_id: 'allowed' });
    const message = { body: event, attempts: 2, ack: vi.fn(), retry: vi.fn() };
    first.mockRejectedValueOnce(new Error('D1 unavailable'));
    await worker.queue({ messages: [message] } as unknown as MessageBatch<Invalidation>, env);
    expect(message.retry).toHaveBeenCalledWith({ delaySeconds: 120 }); expect(message.ack).not.toHaveBeenCalled();
    first.mockResolvedValue({ sent_at: 12345 }); message.retry.mockClear();
    await worker.queue({ messages: [message] } as unknown as MessageBatch<Invalidation>, env);
    expect(message.ack).toHaveBeenCalled(); expect(message.retry).not.toHaveBeenCalled();
  });
  it('retries FCM quota failures and removes an invalid registration', async () => {
    const { env, devices, queries } = environment(); devices.push({ uid: 'uid', fcm_token: 'bad-token', account_id: 'allowed' });
    const { privateKey } = await generateKeyPair('RS256', { extractable: true });
    env.FCM_SERVICE_ACCOUNT = JSON.stringify({ project_id: env.FIREBASE_PROJECT_ID, client_email: 'sender@example.test', private_key: await exportPKCS8(privateKey) });
    let status = 429;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('oauth2.googleapis.com') ? Response.json({ access_token: 'test-access', expires_in: 3600 }) :
      Response.json({ error: { details: [{ errorCode: status === 404 ? 'UNREGISTERED' : 'QUOTA_EXCEEDED' }] } }, { status })));
    const message = { body: event, attempts: 1, ack: vi.fn(), retry: vi.fn() };
    await worker.queue({ messages: [message] } as unknown as MessageBatch<Invalidation>, env);
    expect(message.retry).toHaveBeenCalled(); expect(message.ack).not.toHaveBeenCalled();
    status = 404;
    await worker.queue({ messages: [message] } as unknown as MessageBatch<Invalidation>, env);
    expect(queries.some(q => q.sql === 'DELETE FROM devices WHERE uid = ? AND fcm_token = ?')).toBe(true);
    expect(message.ack).toHaveBeenCalled();
  });
});
