export type BookStatus = 'IMPORTING' | 'READY' | 'FAILED' | 'BROKEN';
export type ImportState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface Book {
  id: string;
  title: string;
  author: string | null;
  coverUrl: string | null;
  status: BookStatus;
  progressRatio: number | null;
  lastReadAt: string;
  createdAt: string;
}

export interface Shelf { items: Book[]; nextCursor: string | null }

export interface ImportError { code: string; message: string; retryable: boolean }

export interface ImportTask {
  importId: string | null;
  bookId: string;
  state: ImportState;
  stage: 'VALIDATING' | 'PARSING' | 'SANITIZING' | 'PUBLISHING';
  attempt: number;
  error: ImportError | null;
  reused: boolean;
}

export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

const STAGE_LABEL: Record<ImportTask['stage'], string> = {
  VALIDATING: '正在校验文件',
  PARSING: '正在解析章节',
  SANITIZING: '正在处理正文',
  PUBLISHING: '正在生成阅读文件',
};

export function stageLabel(stage: ImportTask['stage']): string { return STAGE_LABEL[stage]; }

export function localFileError(file: { name: string; size: number }): string | null {
  if (!file.name.toLowerCase().endsWith('.epub')) return '请选择 .epub 格式的电子书';
  if (file.size <= 0) return '文件为空，请重新选择';
  if (file.size > MAX_UPLOAD_BYTES) return '文件超过 25 MiB 上限，无法导入';
  return null;
}

const ERROR_COPY: Record<string, string> = {
  UPLOAD_TOO_LARGE: '文件超过 25 MiB 上限，无法导入',
  EPUB_FILE_TYPE_REQUIRED: '请选择 .epub 格式的电子书',
  EPUB_INVALID_ZIP: '文件不是有效的 EPUB，请换一个文件',
  EPUB_INVALID_MIMETYPE: '文件不是有效的 EPUB，请换一个文件',
  EPUB_INVALID_CONTAINER: '文件结构缺少必要信息，无法导入',
  EPUB_INVALID_OPF: '文件结构缺少必要信息，无法导入',
  EPUB_INVALID_SPINE: '文件缺少可阅读的正文',
  EPUB_ENCRYPTED: '该书包含加密内容，暂不支持导入',
  EPUB_FIXED_LAYOUT: '暂不支持固定版式 EPUB',
  EPUB_NO_CONTENT: '该书没有可阅读的正文',
  EPUB_PATH_TRAVERSAL: '文件结构不安全，已拒绝导入',
  EPUB_DTD_FORBIDDEN: '文件结构不安全，已拒绝导入',
  EPUB_ENTRY_LIMIT: '文件包含过多资源，无法导入',
  EPUB_ENTRY_TOO_LARGE: '文件中的单个资源过大，无法导入',
  EPUB_TOTAL_TOO_LARGE: '文件解压后超过处理上限，无法导入',
  EPUB_ARTIFACT_TOO_LARGE: '生成的阅读文件超过上限，无法导入',
  EPUB_INDEX_TOO_LARGE: '正文索引超过上限，无法导入',
  EPUB_ARTIFACT_UNDETERMINISTIC: '处理结果不稳定，已停止导入，请反馈该文件',
  IMPORT_TIMEOUT: '导入时间超出限制，请重试或换用更小的文件',
  IMPORT_LIMIT_REACHED: '正在导入的书过多，请等待当前任务完成',
  STORAGE_NOT_CONFIGURED: '本地存储暂不可用，请稍后重试',
  STORAGE_UNAVAILABLE: '本地存储暂不可用，请稍后重试',
  BOOK_DELETING: '该书正在删除，请稍后刷新',
  BOOK_NOT_FOUND: '书籍不存在或已被删除',
  BOOK_STATE_CONFLICT: '书籍状态已变化，请刷新书架',
  NETWORK_ERROR: '网络连接中断，请检查后重试',
  INVALID_REQUEST: '请求参数有误，请刷新后重试',
};

export function errorMessage(code: string | undefined | null): string {
  if (!code) return '导入未完成，请重新选择文件';
  return ERROR_COPY[code] ?? '导入未完成，请稍后再试';
}

export function isTerminal(state: ImportState): boolean { return state === 'SUCCEEDED' || state === 'FAILED'; }

export function pollDelay(elapsedMs: number): number { return elapsedMs < 30_000 ? 1_000 : 3_000; }

export function uploadProgress(loaded: number, total: number): number {
  if (!Number.isFinite(loaded) || !Number.isFinite(total) || total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((loaded / total) * 100)));
}

export function bookStatusLabel(status: BookStatus): string {
  switch (status) {
    case 'READY': return '可阅读';
    case 'IMPORTING': return '正在导入';
    case 'FAILED': return '导入失败';
    case 'BROKEN': return '文件不可用';
  }
}

export function canOpen(status: BookStatus): boolean { return status === 'READY'; }

export function canRetry(status: BookStatus): boolean { return status === 'FAILED' || status === 'BROKEN'; }

export function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(date);
}

export function coverInitial(title: string): string {
  const trimmed = title.trim();
  return trimmed.length === 0 ? '书' : Array.from(trimmed)[0] ?? '书';
}
