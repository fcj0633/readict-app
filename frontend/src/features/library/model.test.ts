import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  bookStatusLabel, canOpen, canRetry, coverInitial, errorMessage, formatDate, isTerminal,
  localFileError, pollDelay, stageLabel, uploadProgress, MAX_UPLOAD_BYTES,
} from './model';

test('rejects wrong extension, empty and oversized files locally', () => {
  assert.equal(localFileError({ name: 'book.pdf', size: 1024 }), '请选择 .epub 格式的电子书');
  assert.equal(localFileError({ name: 'book.epub', size: 0 }), '文件为空，请重新选择');
  assert.equal(localFileError({ name: 'book.epub', size: MAX_UPLOAD_BYTES + 1 }), '文件超过 25 MiB 上限，无法导入');
  assert.equal(localFileError({ name: 'BOOK.EPUB', size: 1024 }), null);
});

test('maps known error codes and falls back for unknown ones', () => {
  assert.equal(errorMessage('EPUB_INVALID_MIMETYPE'), '文件不是有效的 EPUB，请换一个文件');
  assert.equal(errorMessage('EPUB_FIXED_LAYOUT'), '暂不支持固定版式 EPUB');
  assert.equal(errorMessage('IMPORT_LIMIT_REACHED'), '正在导入的书过多，请等待当前任务完成');
  assert.equal(errorMessage('SOMETHING_NEW'), '导入未完成，请稍后再试');
  assert.equal(errorMessage(null), '导入未完成，请重新选择文件');
  assert.ok(!errorMessage('EPUB_INVALID_ZIP').includes('Exception'));
});

test('polls quickly first and backs off after thirty seconds', () => {
  assert.equal(pollDelay(0), 1000);
  assert.equal(pollDelay(29_999), 1000);
  assert.equal(pollDelay(30_000), 3000);
  assert.equal(pollDelay(120_000), 3000);
});

test('computes clamped upload percentage', () => {
  assert.equal(uploadProgress(0, 100), 0);
  assert.equal(uploadProgress(33, 100), 33);
  assert.equal(uploadProgress(100, 100), 100);
  assert.equal(uploadProgress(200, 100), 100);
  assert.equal(uploadProgress(10, 0), 0);
  assert.equal(uploadProgress(Number.NaN, 10), 0);
});

test('describes task and book states without inventing success', () => {
  assert.equal(isTerminal('SUCCEEDED'), true);
  assert.equal(isTerminal('FAILED'), true);
  assert.equal(isTerminal('RUNNING'), false);
  assert.equal(stageLabel('SANITIZING'), '正在处理正文');
  assert.equal(bookStatusLabel('READY'), '可阅读');
  assert.equal(bookStatusLabel('BROKEN'), '文件不可用');
  assert.equal(canOpen('READY'), true);
  assert.equal(canOpen('IMPORTING'), false);
  assert.equal(canRetry('FAILED'), true);
  assert.equal(canRetry('READY'), false);
});

test('formats dates and cover initials defensively', () => {
  assert.equal(formatDate('not-a-date'), '');
  assert.match(formatDate('2026-11-23T02:00:00Z'), /2026/);
  assert.equal(coverInitial('  Moby-Dick '), 'M');
  assert.equal(coverInitial('   '), '书');
});
