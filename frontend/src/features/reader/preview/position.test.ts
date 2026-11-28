import { test } from 'node:test';
import assert from 'node:assert/strict';
import { placeCard } from './position';

const card = { width: 360, height: 420 };
const viewport = { width: 1440, height: 900 };
const anchor = (left: number, top: number) => ({
  left, top, right: left + 80, bottom: top + 22, width: 80, height: 22,
});

test('places the card to the right when there is room', () => {
  const placement = placeCard(anchor(300, 300), card, viewport);
  assert.equal(placement.placement, 'right');
  assert.equal(placement.left, 394);
  assert.equal(placement.top, 292);
  assert.equal(placement.maxHeight, 592);
});

test('flips to the left near the right edge', () => {
  const placement = placeCard(anchor(1300, 300), card, viewport);
  assert.equal(placement.placement, 'left');
  assert.equal(placement.left, 926);
  assert.equal(placement.top, 292);
});

test('reserves readable space at the bottom and caps the scroll height', () => {
  const placement = placeCard(anchor(300, 860), card, viewport);
  assert.equal(placement.top, 464);
  assert.equal(placement.maxHeight, 420);
  assert.ok(placement.top + placement.maxHeight <= 884);
});

test('keeps a narrow viewport card inside the page', () => {
  const placement = placeCard(anchor(40, 40), { width: 600, height: 300 }, { width: 420, height: 600 });
  assert.ok(placement.left >= 16);
  assert.ok(placement.left <= 420);
  assert.equal(placement.top, 72);
  assert.equal(placement.maxHeight, 450);
});
