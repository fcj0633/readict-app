import { test } from 'node:test';
import assert from 'node:assert/strict';
import { countToc, findTocLabel, groupToc, normalizeHref, sameChapter } from './toc';
import type { ManifestTocNode } from './engine/types';

const nodes: ManifestTocNode[] = [
  {
    id: 'part-1', label: 'PART ONE', href: 'OEBPS/Text/ch1.xhtml',
    children: [
      { id: 'ch-1', label: 'CHAPTER ONE', href: 'OEBPS/Text/ch1.xhtml', children: [] },
      { id: 'ch-2', label: 'CHAPTER TWO', href: 'OEBPS/Text/ch2.xhtml#start', children: [] },
    ],
  },
  { id: 'part-2', label: 'PART TWO', href: 'OEBPS/Text/ch3.xhtml', children: [] },
];

test('normalizes fragments and relative prefixes', () => {
  assert.equal(normalizeHref('OEBPS/Text/ch2.xhtml#start'), 'OEBPS/Text/ch2.xhtml');
  assert.equal(normalizeHref('./Text/ch1.xhtml'), 'Text/ch1.xhtml');
});

test('matches chapter hrefs with fragments and path suffixes', () => {
  assert.equal(sameChapter('OEBPS/Text/ch1.xhtml', 'OEBPS/Text/ch1.xhtml'), true);
  assert.equal(sameChapter('OEBPS/Text/ch2.xhtml#start', 'OEBPS/Text/ch2.xhtml'), true);
  assert.equal(sameChapter('Text/ch1.xhtml', 'OEBPS/Text/ch1.xhtml'), true);
  assert.equal(sameChapter('OEBPS/Text/ch1.xhtml', 'OEBPS/Text/ch2.xhtml'), false);
});

test('finds labels and counts nested nodes', () => {
  assert.equal(findTocLabel(nodes, 'OEBPS/Text/ch2.xhtml#start'), 'CHAPTER TWO');
  assert.equal(findTocLabel(nodes, 'OEBPS/Text/ch3.xhtml'), 'PART TWO');
  assert.equal(findTocLabel(nodes, 'OEBPS/Text/missing.xhtml'), undefined);
  assert.equal(countToc(nodes), 4);
});

test('groups a flat PART/CHAPTER manifest into a display tree', () => {
  const flat = [
    { id: 'p1', label: 'PART ONE', href: 'ch1.xhtml', children: [] },
    { id: 'c1', label: 'CHAPTER ONE', href: 'ch1.xhtml', children: [] },
    { id: 'c2', label: 'CHAPTER TWO', href: 'ch2.xhtml', children: [] },
    { id: 'p2', label: 'PART TWO', href: 'ch3.xhtml', children: [] },
    { id: 'c3', label: 'CHAPTER THREE', href: 'ch3.xhtml', children: [] },
  ];
  const grouped = groupToc(flat);
  assert.equal(grouped.length, 2);
  assert.equal(grouped[0]!.children.length, 2);
  assert.equal(grouped[1]!.children[0]!.label, 'CHAPTER THREE');
  assert.equal(findTocLabel(grouped, 'ch2.xhtml'), 'CHAPTER TWO');
});
