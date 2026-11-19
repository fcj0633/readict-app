import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createStatusLoader, getSystemStatus, readStatusResponse } from './api';
const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const id = '00112233-4455-6677-8899-aabbccddeeff';
const status = (requestId = id) => ({ status: 'UP', service: 'readict', requestId });
const json = (body: unknown, code = 200, type = 'application/json') => new Response(JSON.stringify(body), { status: code, headers: { 'Content-Type': type } });
test('validates status JSON', async () => assert.deepEqual(await readStatusResponse(json(status())), status()));
test('preserves a valid 503 Problem Details', async () => {
  await assert.rejects(readStatusResponse(json({ status: 503, code: 'SYSTEM_NOT_READY', title: '服务暂未就绪', requestId: id, retryable: true }, 503, 'application/problem+json')), (e: unknown) => e instanceof ApiError && e.status === 503 && e.code === 'SYSTEM_NOT_READY' && e.requestId === id && e.retryable);
});
test('rejects HTML and malformed JSON without exposing body', async () => {
  for (const response of [new Response('<script>secret</script>'), new Response('{', { headers: { 'Content-Type': 'application/json' } })]) {
    await assert.rejects(readStatusResponse(response), (e: unknown) => e instanceof ApiError && e.code === 'INVALID_RESPONSE' && !e.message.includes('secret'));
  }
});
test('rejects invalid success fields and success with a problem media type', async () => {
  for (const body of [null, [], {}, { ...status(), status: 'DOWN' }, { ...status(), requestId: 'bad' }, { ...status(), service: 'other' }]) {
    await assert.rejects(readStatusResponse(json(body)), ApiError);
  }
  await assert.rejects(readStatusResponse(json(status(), 200, 'application/problem+json')), ApiError);
});
test('does not treat non-2xx JSON or malformed problem as success', async () => {
  await assert.rejects(readStatusResponse(json(status(), 500)), (e: unknown) => e instanceof ApiError && e.code === 'HTTP_ERROR');
  await assert.rejects(readStatusResponse(json({ status: 200, code: 'BAD', title: 'x', retryable: true }, 503, 'application/problem+json')), (e: unknown) => e instanceof ApiError && e.code === 'HTTP_ERROR');
});
test('maps network failure', async () => {
  globalThis.fetch = async () => { throw new TypeError('private network detail'); };
  await assert.rejects(getSystemStatus(), (e: unknown) => e instanceof ApiError && e.code === 'NETWORK_ERROR' && !e.message.includes('private'));
});
test('preserves cancellation', async () => {
  globalThis.fetch = async () => { throw new DOMException('aborted', 'AbortError'); };
  await assert.rejects(getSystemStatus(), (e: unknown) => e instanceof Error && e.name === 'AbortError');
});
test('uses same-origin relative path and supplied signal', async () => {
  const controller = new AbortController();
  globalThis.fetch = async (url, init) => {
    assert.equal(url, '/api/v1/system/status'); assert.equal(init?.credentials, 'same-origin'); assert.equal(init?.signal, controller.signal);
    return json(status());
  };
  await getSystemStatus(controller.signal);
});
test('late response cannot replace newer result and disposal ignores pending response', async () => {
  const responses: ((r: Response) => void)[] = [];
  const signals: (AbortSignal | null | undefined)[] = [];
  globalThis.fetch = async (_url, init) => { signals.push(init?.signal); return new Promise<Response>(resolve => responses.push(resolve)); };
  const states: unknown[] = [];
  const loader = createStatusLoader(value => states.push(value));
  const first = loader.retry(); const second = loader.retry();
  assert.equal(signals[0]?.aborted, true);
  const newer = '11112233-4455-6677-8899-aabbccddeeff';
  responses[1]!(json(status(newer))); await second;
  responses[0]!(json(status())); await first;
  assert.deepEqual(states, [null, null, status(newer)]);
  const third = loader.retry(); loader.dispose(); responses[2]!(json(status())); await third;
  assert.equal(states.at(-1), null);
});
