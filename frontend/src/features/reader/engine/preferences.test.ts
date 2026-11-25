import { test } from 'node:test';
import assert from 'node:assert/strict';
import { themeRules } from './preferences';
import type { ReaderPreferences } from './types';

test('reading themes retain every supported user font size and line height', () => {
  for (const theme of ['warm', 'white', 'dark'] as const) {
    for (const fontSize of [18, 20, 22, 24]) {
      for (const lineHeight of [1.6, 1.8, 2]) {
        const preferences: ReaderPreferences = { theme, fontSize, lineHeight, font: 'Georgia', width: 720 };
        const rules = themeRules(preferences);
        assert.equal(rules.body?.['font-size'], `${fontSize}px`);
        assert.equal(rules.body?.['line-height'], String(lineHeight));
        assert.equal(rules.html?.background, rules.body?.background);
      }
    }
  }
});

test('paragraph rhythm does not impose an indent or important rules on book-specific layout', () => {
  const rules = themeRules({ theme: 'warm', fontSize: 24, lineHeight: 1.8, font: 'Arial', width: 660 });
  assert.equal(rules.p?.['text-indent'], undefined);
  assert.equal(rules.body?.['font-family'], 'Arial, sans-serif');
  assert.equal(rules.body?.['text-align'], 'left');
  assert.ok(!JSON.stringify(rules).includes('!important'));
});
