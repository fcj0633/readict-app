import { request, object, invalid, discardCsrf, loadCsrf, voidResponse } from '../../lib/http';
export type User = { id: string; email: string | null; phone: string | null; emailVerified: boolean; phoneVerified: boolean };
export type Credentials = { identifierType: 'email' | 'phone'; identifier: string; password: string };
export function user(body: unknown): User {
  if (!object(body) || typeof body.id !== 'string' || !/^[0-9a-f-]{36}$/i.test(body.id)
    || !(body.email === null || typeof body.email === 'string') || !(body.phone === null || typeof body.phone === 'string')
    || typeof body.emailVerified !== 'boolean' || typeof body.phoneVerified !== 'boolean') return invalid();
  return { id: body.id, email: body.email, phone: body.phone, emailVerified: body.emailVerified, phoneVerified: body.phoneVerified };
}
const envelope = (body: unknown) => object(body) ? user(body.user) : invalid();
export const me = (signal?: AbortSignal) => request('/auth/me', user, { signal });
export const register = (input: Credentials) => request('/auth/register', envelope, { method: 'POST', body: input });
export async function login(input: Credentials) { await request('/auth/login', envelope, { method: 'POST', body: input }); discardCsrf(); await loadCsrf(); return me(); }
export async function logout() { await request('/auth/logout', voidResponse, { method: 'POST' }); discardCsrf(); }
export async function changePassword(currentPassword: string, newPassword: string) { await request('/auth/password', voidResponse, { method: 'PUT', body: { currentPassword, newPassword } }); discardCsrf(); }
export type Challenge = { challengeId: string; retryAfter: number; expiresIn: number; simulation?: boolean };
function challenge(value: unknown): Challenge {
  if (!object(value) || typeof value.challengeId !== 'string' || typeof value.retryAfter !== 'number' || typeof value.expiresIn !== 'number') return invalid();
  return { challengeId: value.challengeId, retryAfter: value.retryAfter, expiresIn: value.expiresIn, simulation: value.simulation === true };
}
export const requestReset = (identifierType: string, identifier: string) => request('/auth/password-reset/challenges', challenge, { method: 'POST', body: { identifierType, identifier } });
export const requestContact = (identifierType: string) => request('/auth/verification/challenges', challenge, { method: 'POST', body: { identifierType } });
export const verifyContact = (challengeId: string, proof: string) => request('/auth/verification/verify', value => {
  if (!object(value) || typeof value.simulation !== 'boolean' || typeof value.emailVerified !== 'boolean' || typeof value.phoneVerified !== 'boolean') return invalid();
  return value.simulation;
}, { method: 'POST', body: { challengeId, proof } });
export const verifyReset = (challengeId: string, proof: string) => request('/auth/password-reset/verify', value => {
  if (!object(value) || typeof value.resetToken !== 'string' || typeof value.expiresAt !== 'string' || typeof value.simulation !== 'boolean') return invalid();
  return { resetToken: value.resetToken, expiresAt: value.expiresAt, simulation: value.simulation };
}, { method: 'POST', body: { challengeId, proof } });
export const completeReset = (resetToken: string, newPassword: string) => request('/auth/password-reset/complete', voidResponse, { method: 'POST', body: { resetToken, newPassword } });
export function phoneInput(value: string) { return value.trim().startsWith('+86') ? value.trim().slice(3) : value; }
export function passwordValid(value: string) { const size = Array.from(value).length; return size >= 8 && size <= 128 && new TextEncoder().encode(value).length <= 512 && /[0-9]/.test(value) && /[A-Za-z]/.test(value); }
export const passwordHint = '密码不少于 8 位，且至少包含一个数字和一个英文字母';
export function safeReturnTo(value: string | null) { return value && ['/account', '/settings'].includes(value) ? value : '/account'; }
