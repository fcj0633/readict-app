import ePub from 'epubjs';
import type { Book, Contents, Location, Rendition } from 'epubjs';
import { collectBlocks, globalOffsetOf, locate, rangeForGlobal, verifyBlocks, type BlockText } from './blocks';
import { buildQuote, findQuote, quoteMatches } from './anchor';
import { themeRules } from './preferences';
import type {
  ReaderEngine, ReaderEngineOptions, ReaderEvent, ReaderPreferences, ReadingAnchor,
  RestoreResult, TextIndexChapter,
} from './types';

interface ChapterCache { blocks: BlockText[]; index: TextIndexChapter; verified: boolean }

const STABLE_QUIET_MS = 150;
const STABLE_MAX_MS = 2000;

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
      rendition.on('relocated', (location: Location) => this.onRelocated(location, generation));
      await rendition.display();
      await this.waitStable(generation);
      this.assertSandbox();
      this.applyTheme();
      this.attachKeyboard();
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
        return 'chapter-start';
      }
      if (anchor.cfi.length > 0 && anchor.cfi.length <= 4096) {
        try {
          await rendition.display(anchor.cfi);
          await this.waitStable(generation);
          if (await this.contentMatches(anchor)) return 'restored';
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
            return 'quote-fallback';
          }
        }
      }
      return 'chapter-start';
    } finally {
      this.suppress = Math.max(0, this.suppress - 1);
    }
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

  private attachKeyboard(): void {
    const iframe = this.host.querySelector('iframe');
    const document_ = iframe?.contentDocument;
    if (!document_) return;
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') { event.preventDefault(); this.onKey?.('next'); }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); this.onKey?.('previous'); }
      else if (event.key === 'Escape') this.onKey?.('escape');
      else this.onKey?.('other');
    };
    document_.addEventListener('keydown', handler);
    this.cleanups.push(() => document_.removeEventListener('keydown', handler));
  }

  private attachResize(): void {
    const handler = () => {
      const rendition = this.rendition as unknown as { resize?: () => void } | null;
      try { rendition?.resize?.(); } catch { /* engine keeps old size */ }
      this.emit({ type: 'resized' });
    };
    window.addEventListener('resize', handler);
    this.cleanups.push(() => window.removeEventListener('resize', handler));
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
