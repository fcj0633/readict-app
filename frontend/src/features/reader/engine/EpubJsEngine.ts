import ePub from 'epubjs';
import type { Book, Contents, Location, Rendition } from 'epubjs';
import { collectBlocks, globalOffsetOf, locate, rangeForGlobal, verifyBlocks, type BlockText } from './blocks';
import { buildQuote, findQuote, quoteMatches } from './anchor';
import { wordAt, sentenceAt, sentenceWordRange, quoteParts, type TextSelection, type SelectionRect } from './selection';
import { themeRules } from './preferences';
import type {
  ReaderEngine, ReaderEngineOptions, ReaderEvent, ReaderPreferences, ReadingAnchor,
  RestoreResult, TextIndexChapter,
} from './types';

interface ChapterCache { blocks: BlockText[]; index: TextIndexChapter; verified: boolean }

const STABLE_QUIET_MS = 150;
const STABLE_MAX_MS = 2000;

interface CaretPosition { node: Node; offset: number }

function caretAt(document_: Document, x: number, y: number): CaretPosition | null {
  const extended = document_ as Document & {
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
    caretRangeFromPoint?: (x: number, y: number) => Range | null;
  };
  const position = extended.caretPositionFromPoint?.(x, y);
  if (position) return { node: position.offsetNode, offset: position.offset };
  const range = extended.caretRangeFromPoint?.(x, y);
  if (range && range.startContainer.nodeType === Node.TEXT_NODE) {
    return { node: range.startContainer, offset: range.startOffset };
  }
  return null;
}

export class EpubJsEngine implements ReaderEngine {
  private readonly host: HTMLElement;
  private readonly manifest: ReaderEngineOptions['manifest'];
  private readonly loadIndex: ReaderEngineOptions['loadIndex'];
  private readonly onKey?: ReaderEngineOptions['onKey'];
  private preferences: ReaderPreferences;
  private book: Book | null = null;
  private rendition: Rendition | null = null;
  private listeners = new Set<(event: ReaderEvent) => void>();
  private cleanups: (() => void)[] = [];
  private cache = new Map<string, ChapterCache>();
  private generation = 0;
  private suppress = 0;
  private disposed = false;
  private lastLocation: Location | null = null;
  private lastAnchorValue: ReadingAnchor | null = null;
  private lastRelocatedAt = 0;
  private stableTimer: number | undefined;
  private navigating = false;
  private pendingDirection: 'next' | 'previous' | null = null;
  private keyboardDocuments = new WeakSet<Document>();
  private selectionDocuments = new WeakSet<Document>();

  constructor(options: ReaderEngineOptions) {
    this.host = options.host;
    this.manifest = options.manifest;
    this.loadIndex = options.loadIndex;
    this.onKey = options.onKey;
    this.preferences = options.preferences;
  }

  async open(source: ArrayBuffer): Promise<void> {
    const generation = ++this.generation;
    this.suppress++;
    try {
      const book = ePub(source, { replacements: 'blobUrl' }) as unknown as Book;
      this.book = book;
      await book.opened;
      if (generation !== this.generation || this.disposed) return;
      await book.loaded.navigation;
      const rendition = book.renderTo(this.host, {
        manager: 'default', flow: 'paginated', spread: 'none',
        width: '100%', height: '100%', allowScriptedContent: false,
      });
      this.rendition = rendition;
      rendition.hooks.content.register((contents: Contents) => {
        this.attachKeyboard(contents.document);
        this.attachSelection(contents.document);
      });
      rendition.on('relocated', (location: Location) => this.onRelocated(location, generation));
      await rendition.display();
      await this.waitStable(generation);
      this.assertSandbox();
      this.applyTheme();
      this.attachKeyboard();
      this.attachSelection();
      this.attachResize();
      this.emit({ type: 'ready', spineHref: this.currentHref() ?? '' });
    } catch (error) {
      this.cleanupEngine();
      throw error instanceof Error ? error : new Error('ENGINE_INIT_FAILED');
    } finally {
      this.suppress = Math.max(0, this.suppress - 1);
    }
  }

  async display(anchor: ReadingAnchor): Promise<RestoreResult> {
    const rendition = this.rendition;
    if (!rendition || this.disposed) throw new Error('ENGINE_NOT_READY');
    const generation = this.generation;
    this.suppress++;
    try {
      const identity = anchor.artifactId === this.manifest.artifactId
        && anchor.artifactHash === this.manifest.artifactHash
        && anchor.pipelineVersion === this.manifest.pipelineVersion;
      if (!identity) {
        await this.goToSpine(anchor.spineHref, generation);
        if (this.lastLocation) this.lastAnchorValue = await this.buildAnchor(this.lastLocation);
        return 'chapter-start';
      }
      if (anchor.cfi.length > 0 && anchor.cfi.length <= 4096) {
        try {
          await rendition.display(anchor.cfi);
          await this.waitStable(generation);
          if (await this.contentMatches(anchor)) {
            this.lastAnchorValue = { ...anchor };
            return 'restored';
          }
        } catch { /* fall through to text matching */ }
      }
      await this.goToSpine(anchor.spineHref, generation);
      const chapter = await this.chapterBlocks(anchor.spineHref, true);
      if (chapter.verified && anchor.quote) {
        const match = findQuote(chapter.blocks, anchor.quote);
        if (match) {
          const block = chapter.blocks[match.blockIndex]!;
          const range = rangeForGlobal(this.docRoot()!, block.globalStart + match.offset,
            block.globalStart + match.offset + anchor.quote.exact.length);
          const cfi = range ? this.cfiFromRange(range) : null;
          if (cfi) {
            await rendition.display(cfi);
            await this.waitStable(generation);
            this.lastAnchorValue = { ...anchor, cfi };
            return 'quote-fallback';
          }
        }
      }
      if (this.lastLocation) this.lastAnchorValue = await this.buildAnchor(this.lastLocation);
      return 'chapter-start';
    } finally {
      this.suppress = Math.max(0, this.suppress - 1);
    }
  }

  /** Resolve the saved target from verified indexed text, without wrapping EPUB nodes. */
  async favoriteSelection(anchor: ReadingAnchor, wordStart: number, wordEnd: number): Promise<SelectionRect | null> {
    if (this.disposed || anchor.artifactId !== this.manifest.artifactId
      || anchor.artifactHash !== this.manifest.artifactHash || anchor.pipelineVersion !== this.manifest.pipelineVersion
      || this.currentSpine()?.href !== anchor.spineHref || !anchor.quote || anchor.blockOffset === undefined
      || !Number.isSafeInteger(wordStart) || !Number.isSafeInteger(wordEnd) || wordStart < 0 || wordEnd <= wordStart
      || wordEnd > anchor.quote.exact.length) return null;
    const chapter = await this.chapterBlocks(anchor.spineHref);
    const blockIndex = chapter.index.blocks.findIndex(b => b.blockId === anchor.blockId);
    const block = chapter.blocks[blockIndex];
    const root = this.docRoot();
    if (!chapter.verified || !block || !root || !quoteMatches(block.text, anchor.blockOffset, anchor.quote)) return null;
    const range = rangeForGlobal(root, block.globalStart + anchor.blockOffset + wordStart,
      block.globalStart + anchor.blockOffset + wordEnd);
    const iframe = this.host.querySelector('iframe');
    if (!range || !iframe || range.toString() !== anchor.quote.exact.slice(wordStart, wordEnd)) return null;
    const rect = range.getBoundingClientRect(), frame = iframe.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0 || rect.right <= 0 || rect.left >= frame.width
      || rect.bottom <= 0 || rect.top >= frame.height) return null;
    return { left: frame.left + rect.left, top: frame.top + rect.top,
      right: frame.left + rect.right, bottom: frame.top + rect.bottom, width: rect.width, height: rect.height };
  }

  async next(): Promise<void> { await this.navigate('next'); }

  async previous(): Promise<void> { await this.navigate('previous'); }

  async applyPreferences(preferences: ReaderPreferences): Promise<void> {
    this.preferences = preferences;
    this.suppress++;
    try {
      this.applyTheme();
      await this.waitStable(this.generation);
      this.emit({ type: 'resized' });
    } finally {
      this.suppress = Math.max(0, this.suppress - 1);
    }
  }

  anchor(): ReadingAnchor | null { return this.lastAnchorValue; }

  currentSpine(): { href: string; index: number } | null {
    const href = this.currentHref();
    if (!href) return null;
    const item = this.manifest.spine.find(entry => entry.href === href);
    return item ? { href: item.href, index: item.index } : null;
  }

  pageInfo(): { page: number; total: number; atStart: boolean; atEnd: boolean } | null {
    const location = this.lastLocation;
    if (!location) return null;
    return {
      page: location.start.displayed?.page ?? 0,
      total: location.start.displayed?.total ?? 0,
      atStart: location.atStart ?? false,
      atEnd: location.atEnd ?? false,
    };
  }

  subscribe(listener: (event: ReaderEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async destroy(): Promise<void> {
    if (this.disposed) return;
    this.disposed = true;
    this.generation++;
    window.clearTimeout(this.stableTimer);
    for (const cleanup of this.cleanups.splice(0)) cleanup();
    this.listeners.clear();
    this.cache.clear();
    this.cleanupEngine();
    this.host.innerHTML = '';
  }

  private cleanupEngine(): void {
    const rendition = this.rendition;
    const book = this.book;
    this.rendition = null;
    this.book = null;
    try { rendition?.destroy(); } catch { /* already gone */ }
    try { book?.destroy(); } catch { /* already gone */ }
    this.lastLocation = null;
    this.lastAnchorValue = null;
  }

  private async navigate(direction: 'next' | 'previous'): Promise<void> {
    if (this.disposed || this.suppress > 0) return;
    if (this.navigating) { this.pendingDirection = direction; return; }
    this.navigating = true;
    try {
      const rendition = this.rendition;
      if (!rendition) return;
      if (direction === 'next') await rendition.next();
      else await rendition.prev();
    } catch { /* boundary or teardown */ }
    finally {
      this.navigating = false;
      const pending = this.pendingDirection;
      this.pendingDirection = null;
      if (pending) void this.navigate(pending);
    }
  }

  private onRelocated(location: Location, generation: number): void {
    if (this.disposed || generation !== this.generation) return;
    this.lastLocation = location;
    this.lastRelocatedAt = performance.now();
    if (this.suppress > 0) return;
    window.clearTimeout(this.stableTimer);
    this.stableTimer = window.setTimeout(() => { void this.publishStable(generation); }, STABLE_QUIET_MS);
  }

  private async publishStable(generation: number): Promise<void> {
    if (this.disposed || generation !== this.generation || this.suppress > 0) return;
    const location = this.lastLocation;
    if (!location) return;
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    if (this.disposed || generation !== this.generation || this.suppress > 0) return;
    const anchor = await this.buildAnchor(location);
    if (this.disposed || generation !== this.generation) return;
    this.lastAnchorValue = anchor;
    const href = this.currentHref() ?? '';
    this.emit({
      type: 'relocated', spineHref: href, cfi: location.start.cfi,
      atStart: location.atStart ?? false, atEnd: location.atEnd ?? false,
      page: location.start.displayed?.page ?? 0, totalPages: location.start.displayed?.total ?? 0,
      anchor,
    });
  }

  private async buildAnchor(location: Location): Promise<ReadingAnchor | null> {
    const href = this.mapSpineHref(location.start.href);
    if (!href) return null;
    const anchor: ReadingAnchor = {
      artifactId: this.manifest.artifactId,
      artifactHash: this.manifest.artifactHash,
      pipelineVersion: this.manifest.pipelineVersion,
      spineHref: href,
      cfi: location.start.cfi,
    };
    const range = this.rangeFor(location.start.cfi);
    const root = this.docRoot();
    if (!range || !root) return anchor;
    const global = globalOffsetOf(root, range.startContainer, range.startOffset);
    if (global === null) { console.warn('anchor-debug', 'global-null'); return anchor; }
    const chapter = await this.chapterBlocks(href);
    const position = locate(chapter.blocks, global);
    if (!position) return anchor;
    const block = chapter.blocks[position.blockIndex]!;
    anchor.blockId = chapter.index.blocks[position.blockIndex]?.blockId ?? `b-${position.blockIndex + 1}`;
    anchor.blockOffset = position.blockOffset;
    anchor.quote = buildQuote(block.text, position.blockOffset);
    return anchor;
  }

  private async contentMatches(anchor: ReadingAnchor): Promise<boolean> {
    const location = this.lastLocation;
    if (!location) return false;
    const range = this.rangeFor(location.start.cfi);
    const root = this.docRoot();
    if (!range || !root) return false;
    const global = globalOffsetOf(root, range.startContainer, range.startOffset);
    if (global === null) return false;
    const href = this.mapSpineHref(location.start.href);
    if (!href || href !== anchor.spineHref) return false;
    const chapter = await this.chapterBlocks(href);
    const position = locate(chapter.blocks, global);
    if (!position) return false;
    if (!anchor.quote) return true;
    return quoteMatches(chapter.blocks[position.blockIndex]!.text, position.blockOffset, anchor.quote);
  }

  private async goToSpine(spineHref: string, generation: number): Promise<void> {
    const rendition = this.rendition;
    if (!rendition) throw new Error('ENGINE_NOT_READY');
    const item = this.manifest.spine.find(entry => entry.href === spineHref);
    if (item) await rendition.display(item.index);
    else if (spineHref) await rendition.display(spineHref);
    else await rendition.display();
    await this.waitStable(generation);
  }

  private async chapterBlocks(spineHref: string, refresh = false): Promise<ChapterCache> {
    const cached = this.cache.get(spineHref);
    if (cached && !refresh) return cached;
    const index = await this.loadIndex(spineHref);
    const root = this.docRoot();
    const blocks = root ? collectBlocks(root) : [];
    const verified = root ? await verifyBlocks(blocks, index.blocks.map(block => ({
      blockId: block.blockId, length: block.utf16Length, hash: block.textHash,
    }))) : false;
    const value: ChapterCache = { blocks, index, verified };
    this.cache.set(spineHref, value);
    return value;
  }

  private waitStable(generation: number): Promise<void> {
    return new Promise(resolve => {
      const started = performance.now();
      const check = () => {
        if (this.disposed || generation !== this.generation) { resolve(); return; }
        const elapsed = performance.now() - started;
        const quiet = performance.now() - this.lastRelocatedAt >= STABLE_QUIET_MS;
        if ((quiet && elapsed >= 100) || elapsed >= STABLE_MAX_MS) resolve();
        else requestAnimationFrame(check);
      };
      requestAnimationFrame(check);
    });
  }

  private applyTheme(): void {
    const themes = this.rendition?.themes;
    if (!themes) return;
    themes.default(themeRules(this.preferences));
  }

  private assertSandbox(): void {
    const iframe = this.host.querySelector('iframe');
    if (!iframe) throw new Error('IFRAME_UNSAFE');
    const sandbox = iframe.getAttribute('sandbox') ?? '';
    if (sandbox.length === 0 || /allow-scripts/i.test(sandbox)) throw new Error('IFRAME_UNSAFE');
  }

  private attachKeyboard(document_?: Document): void {
    const iframe = this.host.querySelector('iframe');
    document_ ??= iframe?.contentDocument ?? undefined;
    if (!document_ || this.keyboardDocuments.has(document_)) return;
    this.keyboardDocuments.add(document_);
    const keyboardDocument = document_;
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') { event.preventDefault(); this.onKey?.('next'); }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); this.onKey?.('previous'); }
      else if (event.key === 'Escape') this.onKey?.('escape');
      else this.onKey?.('other');
    };
    document_.addEventListener('keydown', handler);
    this.cleanups.push(() => keyboardDocument.removeEventListener('keydown', handler));
  }

  private attachSelection(document_?: Document): void {
    const iframe = this.host.querySelector('iframe');
    document_ ??= iframe?.contentDocument ?? undefined;
    if (!document_ || this.selectionDocuments.has(document_)) return;
    this.selectionDocuments.add(document_);
    const selectionDocument = document_;
    const handler = (event: MouseEvent) => {
      if (event.button !== 0 || event.defaultPrevented) return;
      const target = event.target instanceof Element ? event.target : null;
      if (target?.closest('a,button,input,textarea,select,[role="button"],[contenteditable="true"]')) return;
      const current = selectionDocument.getSelection();
      if (current && !current.isCollapsed) return;
      void this.selectionAt(selectionDocument, event.clientX, event.clientY).then(result => {
        if (this.disposed) return;
        if (result && 'error' in result) this.emit({ type: 'selection', selection: null, error: result.error });
        else if (result) this.emit({ type: 'selection', selection: result });
      }).catch(() => { /* A click outside a valid block is not a query. */ });
    };
    selectionDocument.addEventListener('pointerup', handler);
    this.cleanups.push(() => selectionDocument.removeEventListener('pointerup', handler));
  }

  private async selectionAt(document_: Document, clientX: number, clientY: number):
    Promise<TextSelection | { error: 'TEXT_INDEX_MISMATCH' } | null> {
    const position = caretAt(document_, clientX, clientY);
    if (!position || position.node.nodeType !== Node.TEXT_NODE) return null;
    const href = this.currentHref();
    const root = this.docRoot();
    if (!href || !root) return null;
    const global = globalOffsetOf(root, position.node, position.offset);
    if (global === null) return null;
    const chapter = await this.chapterBlocks(href);
    if (!chapter.verified) return { error: 'TEXT_INDEX_MISMATCH' };
    const located = locate(chapter.blocks, global);
    if (!located) return null;
    const block = chapter.blocks[located.blockIndex]!;
    const indexBlock = chapter.index.blocks[located.blockIndex]!;
    const word = wordAt(block.text, located.blockOffset);
    if (!word) return null;
    const sentence = sentenceAt(indexBlock.sentences.map(value => ({
      start: value.start, end: value.end, kind: value.kind as 'SENTENCE' | 'FRAGMENT',
    })), word.start, word.end);
    if (!sentence) return null;
    const wordRange = rangeForGlobal(root, block.globalStart + word.start, block.globalStart + word.end);
    if (!wordRange) return null;
    const rect = wordRange.getBoundingClientRect();
    if (!(clientX >= rect.left - 2 && clientX <= rect.right + 2 && clientY >= rect.top - 2 && clientY <= rect.bottom + 2)) {
      return null;
    }
    const sentenceRange = rangeForGlobal(root, block.globalStart + sentence.start, block.globalStart + sentence.end);
    const contents = this.contents();
    const wordCfi = contents?.cfiFromRange(wordRange);
    const sentenceCfi = sentenceRange ? contents?.cfiFromRange(sentenceRange) : null;
    if (!wordCfi || !sentenceCfi) return null;
    const iframe = this.host.querySelector('iframe');
    if (!iframe) return null;
    const frame = iframe.getBoundingClientRect();
    const surface = block.text.slice(word.start, word.end);
    const sentenceText = block.text.slice(sentence.start, sentence.end);
    return {
      contextKind: sentence.kind === 'FRAGMENT' ? 'fragment' : 'sentence',
      rect: {
        left: frame.left + rect.left, top: frame.top + rect.top,
        right: frame.left + rect.right, bottom: frame.top + rect.bottom,
        width: rect.width, height: rect.height,
      },
      source: {
        artifactId: this.manifest.artifactId,
        spineHref: href,
        blockId: indexBlock.blockId,
        sentenceStart: sentence.start,
        sentenceEnd: sentence.end,
        sentence: sentenceText,
        surface,
        ...sentenceWordRange(word, sentence),
        wordRangeCfi: wordCfi,
        sentenceRangeCfi: sentenceCfi,
        quote: quoteParts(block.text, sentence.start, sentence.end),
      },
    };
  }

  private attachResize(): void {
    let frame = 0;
    let previousWidth = 0;
    let previousHeight = 0;
    let pendingAnchor: ReadingAnchor | null = null;
    const handler = () => {
      pendingAnchor ??= this.lastAnchorValue;
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        if (this.disposed || !this.rendition) return;
        const width = this.host.clientWidth;
        const height = this.host.clientHeight;
        if (!width || !height || (width === previousWidth && height === previousHeight)) {
          pendingAnchor = null;
          return;
        }
        previousWidth = width;
        previousHeight = height;
        const anchor = pendingAnchor;
        pendingAnchor = null;
        // epub.js 0.3.93 supports this CFI argument; its bundled type omits it.
        const resize = this.rendition.resize.bind(this.rendition) as
          (width: number, height: number, cfi?: string) => void;
        resize(width, height, anchor?.cfi);
        if (anchor) {
          // Native CFI relocation alone can drift after reflow; reuse verified quote recovery.
          void this.display(anchor).then(() => {
            if (!this.disposed) this.emit({ type: 'resized' });
          }).catch(() => {
            if (!this.disposed) console.warn('reader-resize-restore-failed');
          });
        }
        this.emit({ type: 'resized' });
      });
    };
    const observer = new ResizeObserver(handler);
    observer.observe(this.host);
    window.addEventListener('resize', handler);
    this.cleanups.push(() => {
      observer.disconnect();
      cancelAnimationFrame(frame);
      window.removeEventListener('resize', handler);
    });
  }

  private currentHref(): string | null {
    const location = this.lastLocation;
    return location ? this.mapSpineHref(location.start.href) : null;
  }

  private mapSpineHref(href: string | undefined): string | null {
    if (!href) return null;
    const direct = this.manifest.spine.find(item => item.href === href);
    if (direct) return direct.href;
    const suffix = this.manifest.spine.find(item => item.href.endsWith('/' + href) || href.endsWith('/' + item.href));
    return suffix ? suffix.href : null;
  }

  private rangeFor(cfi: string): Range | null {
    try {
      const range = this.rendition?.getRange(cfi) as unknown;
      // The Range belongs to the iframe document, so instanceof against the parent Range fails.
      if (range && typeof (range as Range).startContainer !== 'undefined') return range as Range;
      return null;
    } catch { return null; }
  }

  private cfiFromRange(range: Range): string | null {
    try {
      const contents = this.contents();
      return contents?.cfiFromRange(range) ?? null;
    } catch { return null; }
  }

  private contents(): Contents | null {
    const value = this.rendition?.getContents() as unknown;
    if (Array.isArray(value)) return (value[0] as Contents | undefined) ?? null;
    return (value as Contents | null) ?? null;
  }

  private docRoot(): Element | null {
    return this.contents()?.document?.body ?? null;
  }

  private emit(event: ReaderEvent): void {
    for (const listener of this.listeners) listener(event);
  }
}
