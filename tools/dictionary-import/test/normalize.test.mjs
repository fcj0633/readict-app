import test from 'node:test';
import assert from 'node:assert/strict';
import { normalize } from '../src/normalize.mjs';
for (const [input, expected] of [["“Runs”", 'runs'], ['ＦＯＯ', 'foo'], ['École', 'école'], ["can’t", "can't"], ['mother-in-law', 'mother-in-law'], ['(book)', 'book'], ['e\u0301', 'é']]) test('normalizes ' + input, () => assert.equal(normalize(input), expected));
for (const input of ['', '123', 'two words', 'bad\u0000', '\ud800', 'x'.repeat(129)]) test('rejects ' + JSON.stringify(input), () => assert.throws(() => normalize(input), /UNSUPPORTED_QUERY/));
