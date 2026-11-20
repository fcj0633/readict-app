import { ApiError } from './api';
export const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
let csrf: { token: string; headerName: string } | undefined;
let csrfRequest: Promise<void> | undefined;
let epoch = 0;
export function discardCsrf() { epoch++; csrf = undefined; csrfRequest = undefined; }
async function read(response: Response): Promise<unknown> {
  if (response.status === 204 && response.ok) return undefined;
  const content = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase();
  if (content !== 'application/json' && content !== 'application/problem+json') throw new ApiError(response.status, 'INVALID_RESPONSE', '服务响应无法识别', undefined, true);
  let body: unknown;
  try { body = await response.json(); } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务响应无法识别', undefined, true);
  }
  if (!response.ok) {
    const requestId = object(body) && typeof body.requestId === 'string' ? body.requestId : undefined;
    const code = object(body) && typeof body.code === 'string' && /^[A-Z_]{1,64}$/.test(body.code) ? body.code : 'HTTP_ERROR';
    const title = object(body) && typeof body.title === 'string' && body.title.length < 160 ? body.title : '请求未完成';
    if (code === 'CSRF_MISSING' || code === 'CSRF_INVALID') { discardCsrf(); void loadCsrf().catch(() => {}); }
    if (response.status === 401 && typeof window !== 'undefined') window.dispatchEvent(new Event('readict-session-expired'));
    throw new ApiError(response.status, code, title, requestId, response.status >= 500 || response.status === 429);
  }
  return body;
}
async function send(path: string, init: RequestInit): Promise<unknown> {
  let response: Response;
  try { response = await fetch('/api/v1' + path, { ...init, credentials: 'same-origin' }); }
  catch (error) { if (error instanceof Error && error.name === 'AbortError') throw error; throw new ApiError(0, 'NETWORK_ERROR', '网络连接中断，请检查后重试', undefined, true); }
  return read(response);
}
export async function loadCsrf(): Promise<void> {
  if (csrf) return;
  if (!csrfRequest) {
    const current = epoch;
    csrfRequest = send('/auth/csrf', { headers: { Accept: 'application/json' } }).then(value => {
      if (!object(value) || typeof value.token !== 'string' || value.headerName !== 'X-CSRF-TOKEN') throw new ApiError(0, 'INVALID_RESPONSE', '认证响应无法识别');
      if (current === epoch) csrf = { token: value.token, headerName: value.headerName };
    }).finally(() => { if (current === epoch) csrfRequest = undefined; });
  }
  await csrfRequest;
}
export async function request<T>(path: string, decode: (body: unknown) => T, options: { method?: 'GET' | 'POST' | 'PUT'; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  const method = options.method ?? 'GET';
  if (method !== 'GET') await loadCsrf();
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && csrf) headers[csrf.headerName] = csrf.token;
  return decode(await send(path, { method, headers, signal: options.signal, body: options.body === undefined ? undefined : JSON.stringify(options.body) }));
}
export function voidResponse(body: unknown): void { if (body !== undefined) throw new ApiError(0, 'INVALID_RESPONSE', '服务响应无法识别'); }
export function invalid(): never { throw new ApiError(0, 'INVALID_RESPONSE', '服务响应无法识别'); }
