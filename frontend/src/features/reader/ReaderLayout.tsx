import { ArrowLeft, Bookmark, List, Settings2 } from 'lucide-react';
import type { ProgressStatus } from './progress/model';

export type ReaderPanelName = 'none' | 'contents' | 'settings';

export function ReaderTopbar({ title, chapter, onBack }: {
  title: string; chapter?: string; onBack: () => void;
}) {
  return <header className="reader-topbar">
    <button type="button" className="reader-back" onClick={onBack} aria-label="返回书架">
      <ArrowLeft size={16} strokeWidth={1.7} aria-hidden="true" />
      <span>书架</span>
    </button>
    <div className="reader-title">
      <h1 title={title}>{title}</h1>
      {chapter && <p title={chapter}>{chapter}</p>}
    </div>
    <span aria-hidden="true" />
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
      <List size={21} strokeWidth={1.6} aria-hidden="true" />
    </button>
    <button type="button" className="reader-tool" data-label="收藏（M06）"
      aria-label="收藏将在 M06 接入" title="收藏将在 M06 接入" disabled onClick={onFavorites}>
      <Bookmark size={20} strokeWidth={1.6} aria-hidden="true" />
    </button>
    <button type="button" className={`reader-tool${active === 'settings' ? ' active' : ''}`}
      data-label="设置" aria-label="阅读设置" aria-pressed={active === 'settings'} onClick={onSettings}>
      <Settings2 size={21} strokeWidth={1.6} aria-hidden="true" />
    </button>
  </nav>;
}

const LABELS: Record<Exclude<ProgressStatus, 'conflict'>, string> = {
  idle: '', saving: '保存中…', saved: '已保存', unsynced: '未同步',
};

export function SaveStatus({ status, onRetry }: { status: ProgressStatus; onRetry: () => void }) {
  if (status === 'conflict' || status === 'idle') return null;
  if (status === 'unsynced') {
    return <span className="reader-save unsynced" role="status">
      未同步
      <button type="button" onClick={onRetry}>重试</button>
    </span>;
  }
  return <span className={`reader-save ${status}`} role="status">{LABELS[status]}</span>;
}
