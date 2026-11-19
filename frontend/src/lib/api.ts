export type SystemStatus = { status: 'UP'; service: 'readict'; requestId: string };
export class ApiError extends Error {
  constructor(public status: number, public code: string, public title: string,
    public requestId?: string, public retryable = false) { super(title); this.name = 'ApiError'; }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
export async function readStatusResponse(response: Response): Promise<SystemStatus> {
  const headerId = response.headers.get('X-Request-Id');
  const requestId = headerId && uuid.test(headerId) ? headerId : undefined;
  const contentType = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase();
  if (contentType !== 'application/json' && contentType !== 'application/problem+json') {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法识别的响应', requestId, true);
  }
  let body: unknown;
  try { body = await response.json(); }
  catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法识别的响应', requestId, true);
  }
  const bodyId = record(body) && typeof body.requestId === 'string' && uuid.test(body.requestId) ? body.requestId : requestId;
  if (!response.ok) {
    if (contentType === 'application/problem+json' && record(body) && body.status === response.status
      && typeof body.code === 'string' && /^[A-Z_]{1,64}$/.test(body.code)
      && typeof body.title === 'string' && body.title.length <= 120 && typeof body.retryable === 'boolean') {
      throw new ApiError(response.status, body.code, body.title, bodyId, body.retryable);
    }
    throw new ApiError(response.status, 'HTTP_ERROR', '暂时无法连接服务', bodyId, response.status >= 500);
  }
  if (contentType !== 'application/json' || !record(body) || body.status !== 'UP' || body.service !== 'readict'
    || typeof body.requestId !== 'string' || !uuid.test(body.requestId)) {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法识别的响应', bodyId, true);
  }
  return { status: 'UP', service: 'readict', requestId: body.requestId };
}
export async function getSystemStatus(signal?: AbortSignal): Promise<SystemStatus> {
  let response: Response;
  try { response = await fetch('/api/v1/system/status', { credentials: 'same-origin', signal, headers: { Accept: 'application/json' } }); }
  catch (error) {
    if (signal?.aborted || (error instanceof Error && error.name === 'AbortError')) throw error;
    throw new ApiError(0, 'NETWORK_ERROR', '暂时无法连接服务', undefined, true);
  }
  return readStatusResponse(response);
}
/** Cancels previous attempts and ignores late responses even when fetch ignores abort. */
export function createStatusLoader(onState: (value: SystemStatus | ApiError | null) => void) {
  let generation = 0;
  let controller: AbortController | undefined;
  return {
    async retry() {
      const current = ++generation;
      controller?.abort();
      controller = new AbortController();
      const signal = controller.signal;
      onState(null);
      try { const value = await getSystemStatus(signal); if (current === generation && !signal.aborted) onState(value); }
      catch (error) {
        if (current !== generation || signal.aborted) return;
        onState(error instanceof ApiError ? error : new ApiError(0, 'NETWORK_ERROR', '暂时无法连接服务', undefined, true));
      }
    },
    dispose() { generation++; controller?.abort(); },
  };
}
