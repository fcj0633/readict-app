import type { Preferences } from '../../settings/model';
import type { TextSelection } from './selection';

export interface ManifestSpineItem {
  chapterId: string;
  href: string;
  index: number;
  title: string;
  textLength: number;
}

export interface ManifestTocNode {
  id: string;
  label: string;
  href: string;
  children: ManifestTocNode[];
}

export interface ReaderManifest {
  bookId: string;
  artifactId: string;
  artifactHash: string;
  pipelineVersion: string;
  epubUrl: string;
  textIndexUrl: string;
  spine: ManifestSpineItem[];
  toc: ManifestTocNode[];
  settings: Preferences;
  settingsVersion: number;
}

export interface Quote { exact: string; prefix: string; suffix: string }

export interface ReadingAnchor {
  artifactId: string;
  artifactHash: string;
  pipelineVersion: string;
  spineHref: string;
  cfi: string;
  blockId?: string;
  blockOffset?: number;
  quote?: Quote;
}

export interface ReadingProgress {
  bookId: string;
  anchor: ReadingAnchor;
  progressRatio: number;
  version: number;
  updatedAt: string;
}

export interface TextIndexSentence { start: number; end: number; kind: 'SENTENCE' | 'FRAGMENT' }

export interface TextIndexBlock {
  blockId: string;
  utf16Length: number;
  textHash: string;
  sentences: TextIndexSentence[];
}

export interface TextIndexChapter {
  spineHref: string;
  spineIndex: number;
  totalUtf16: number;
  blocks: TextIndexBlock[];
}

export interface ReaderPreferences {
  font: string;
  fontSize: number;
  lineHeight: number;
  width: number;
  theme: 'white' | 'warm' | 'dark';
}

export type ReaderPhase =
  | 'idle' | 'loading' | 'verifying' | 'rendering'
  | 'restoring' | 'ready' | 'reflowing' | 'error' | 'disposed';

export type ReaderErrorCode =
  | 'BOOK_NOT_READY' | 'ARTIFACT_REVOKED' | 'ARTIFACT_HASH_MISMATCH'
  | 'ENGINE_INIT_FAILED' | 'ANCHOR_INVALID' | 'TEXT_INDEX_MISMATCH'
  | 'REFLOW_TIMEOUT' | 'IFRAME_UNSAFE' | 'PROGRESS_CONFLICT';

export type ReaderEvent =
  | { type: 'ready'; spineHref: string }
  | { type: 'relocated'; spineHref: string; cfi: string; atStart: boolean; atEnd: boolean;
      page: number; totalPages: number; anchor: ReadingAnchor | null }
  | { type: 'selection'; selection: TextSelection | null; error?: 'TEXT_INDEX_MISMATCH' }
  | { type: 'resized' }
  | { type: 'error'; code: ReaderErrorCode; message: string };

export type RestoreResult = 'restored' | 'quote-fallback' | 'chapter-start';

export interface ReaderEngineOptions {
  host: HTMLElement;
  manifest: ReaderManifest;
  preferences: ReaderPreferences;
  loadIndex: (spineHref: string) => Promise<TextIndexChapter>;
  onKey?: (key: 'next' | 'previous' | 'escape' | 'other') => void;
}

export interface ReaderEngine {
  open(source: ArrayBuffer): Promise<void>;
  display(anchor: ReadingAnchor): Promise<RestoreResult>;
  next(): Promise<void>;
  previous(): Promise<void>;
  applyPreferences(preferences: ReaderPreferences): Promise<void>;
  anchor(): ReadingAnchor | null;
  currentSpine(): { href: string; index: number } | null;
  pageInfo(): { page: number; total: number; atStart: boolean; atEnd: boolean } | null;
  subscribe(listener: (event: ReaderEvent) => void): () => void;
  destroy(): Promise<void>;
}

export interface ReaderError { code: ReaderErrorCode; message: string }
