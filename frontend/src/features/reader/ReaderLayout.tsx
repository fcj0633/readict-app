import { ArrowLeft, BookOpen, Bookmark, List, Settings2 } from 'lucide-react';
import type { ProgressStatus } from './progress/model';

export type ReaderPanelName = 'none' | 'contents' | 'settings';

export function ReaderTopbar({ onBack }: {
  onBack: () => void;
}) {
  return <header className="reader-topbar">
    <span className="reader-brand"><BookOpen size={25} strokeWidth={1.4} aria-hidden="true" />Readict</span>
    <span className="reader-brand-divider" aria-hidden="true" />
    <button type="button" className="reader-back" onClick={onBack} aria-label="返回书架">
      <ArrowLeft size={16} strokeWidth={1.7} aria-hidden="true" />
      <span>书架</span>
    </button>
  </header>;
}

export function ReaderPaperHeading({ title, chapter }: { title: string; chapter?: string }) {
  return <header className="reader-paper-heading">
    <h1 title={title}>{title}</h1>
    <div className="reader-chapter">
      <span aria-hidden="true" />
      <p title={chapter}>{chapter || '正在展开书页…'}</p>
      <span aria-hidden="true" />
    </div>
  </header>;
}

export function ReaderRail({ active, onToc, onFavorites, onSettings }: {
  active: ReaderPanelName;
  onToc: () => void;
  onFavorites: () => void;
  onSettings: () => void;
}) {
  return <nav className="reader-rail" aria-label="阅读工具">
    <button type="button" className={`reader-tool${active === 'contents' ? ' active' : ''}`}
      data-label="目录" aria-label="章节目录" aria-pressed={active === 'contents'} onClick={onToc}>
      <List size={24} strokeWidth={1.6} aria-hidden="true" />
    </button>
    <button type="button" className="reader-tool" data-label="收藏"
      aria-label="查看收藏" title="查看收藏" onClick={onFavorites}>
      <Bookmark size={23} strokeWidth={1.6} aria-hidden="true" />
    </button>
    <button type="button" className={`reader-tool${active === 'settings' ? ' active' : ''}`}
      data-label="设置" aria-label="阅读设置" aria-pressed={active === 'settings'} onClick={onSettings}>
      <Settings2 size={24} strokeWidth={1.6} aria-hidden="true" />
    </button>
  </nav>;
}

export function SaveStatus({ status, onRetry }: { status: ProgressStatus; onRetry: () => void }) {
  if (status !== 'unsynced') return null;
  return <span className="reader-save unsynced" role="status">
    未同步
    <button type="button" onClick={onRetry}>重试</button>
  </span>;
}
