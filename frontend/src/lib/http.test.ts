import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { request, discardCsrf, voidResponse } from './http';
import { ApiError } from './api';
const original = globalThis.fetch;
beforeEach(() => discardCsrf()); afterEach(() => { globalThis.fetch = original; discardCsrf(); });
const json = (body: unknown, status = 200, type = 'application/json') => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
test('fetches CSRF for mutations, sends same-origin header and handles 204', async () => {
  const calls: string[] = [];
  globalThis.fetch = async (url, options) => { calls.push(String(url)); assert.equal(options?.credentials, 'same-origin');
    if (String(url).endsWith('/csrf')) return json({ token: 'masked-token', headerName: 'X-CSRF-TOKEN' });
    assert.equal(new Headers(options?.headers).get('X-CSRF-TOKEN'), 'masked-token'); return new Response(null, { status: 204 });
  };
  await request('/auth/logout', voidResponse, { method: 'POST' });
  assert.deepEqual(calls, ['/api/v1/auth/csrf', '/api/v1/auth/logout']);
});
test('CSRF failure refreshes token but never replays a write', async () => {
  let writes = 0; let tokens = 0;
  globalThis.fetch = async url => { if (String(url).endsWith('/csrf')) { tokens++; return json({ token: 'token-' + tokens, headerName: 'X-CSRF-TOKEN' }); }
    writes++; return json({ status: 403, code: 'CSRF_INVALID', title: '请重试', retryable: false }, 403, 'application/problem+json');
  };
  await assert.rejects(request('/auth/password', voidResponse, { method: 'PUT', body: {} }), (error: unknown) => error instanceof ApiError && error.code === 'CSRF_INVALID');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(writes, 1); assert.equal(tokens, 2);
});
test('ordinary forbidden does not trigger token refresh loop', async () => {
  let tokens = 0;
  globalThis.fetch = async url => { if (String(url).endsWith('/csrf')) { tokens++; return json({ token: 'token', headerName: 'X-CSRF-TOKEN' }); }
    return json({ code: 'FORBIDDEN', title: '拒绝', status: 403 }, 403, 'application/problem+json');
  };
  await assert.rejects(request('/forbidden', voidResponse, { method: 'POST' }), ApiError);
  await new Promise(resolve => setImmediate(resolve)); assert.equal(tokens, 1);
});
test('rejects malformed JSON, HTML and propagates abort without replay', async () => {
  for (const response of [new Response('<html>private</html>'), new Response('{', { headers: { 'Content-Type': 'application/json' } })]) {
    globalThis.fetch = async () => response; await assert.rejects(request('/test', value => value), ApiError);
  }
  globalThis.fetch = async () => { throw new DOMException('cancel', 'AbortError'); };
  await assert.rejects(request('/test', value => value), (error: unknown) => error instanceof Error && error.name === 'AbortError');
});
