import { test } from 'node:test';
import assert from 'node:assert/strict';
import { user, phoneInput, passwordValid, safeReturnTo } from './api';
test('phone paste removes exactly one supported prefix and keeps other country input invalid', () => {
  assert.equal(phoneInput('+8613800138000'), '13800138000'); assert.equal(phoneInput('13800138000'), '13800138000');
  assert.equal(phoneInput('+12125551234'), '+12125551234'); assert.equal(phoneInput('+86+8613800138000'), '+8613800138000');
});
test('password code-point policy matches accepted and rejected examples', () => {
  for (const value of ['abc12345', 'ABC12345', 'Abc12345', 'abc1234!']) assert.equal(passwordValid(value), true);
  for (const value of ['abcdefgh', '12345678', 'Abcdefgh', 'abc1234', 'a1' + '😀'.repeat(127)]) assert.equal(passwordValid(value), false);
  assert.equal(passwordValid('a1' + '😀'.repeat(126)), true);
});
test('decodes user independently of status responses and disallows external redirects', () => {
  const value = { id: '00112233-4455-6677-8899-aabbccddeeff', email: null, phone: '+8613800138000', emailVerified: false, phoneVerified: false };
  assert.deepEqual(user(value), value); assert.throws(() => user({ status: 'UP' }));
  for (const target of ['https://example.com', '//example.com', 'javascript:alert(1)', '/books']) assert.equal(safeReturnTo(target), '/account');
  assert.equal(safeReturnTo('/settings'), '/settings');
});
