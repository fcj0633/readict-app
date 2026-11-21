export const normalizationVersion = 'col-normalize-v1';
export function normalize(value) {
  if (typeof value !== 'string' || !value.isWellFormed()) throw new Error('UNSUPPORTED_QUERY');
  if ([...value].length > 128 || Buffer.byteLength(value, 'utf8') > 512 || /[\p{Cc}\p{Cf}]/u.test(value)) throw new Error('UNSUPPORTED_QUERY');
  const key = value.normalize('NFKC').toLowerCase().replace(/[‘’]/g, "'").replace(/^[^\p{L}\p{N}\p{M}]+|[^\p{L}\p{N}\p{M}]+$/gu, '');
  if (!/^[\p{L}\p{N}\p{M}]+(?:[-'][\p{L}\p{N}\p{M}]+)*$/u.test(key) || !/\p{L}/u.test(key)) throw new Error('UNSUPPORTED_QUERY');
  return key;
}
