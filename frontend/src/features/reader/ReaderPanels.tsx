import { useEffect, useRef, type ReactNode, type RefObject } from 'react';
import { Check, RefreshCw, X } from 'lucide-react';
import type { Preferences, SaveState } from '../settings/model';
import type { ManifestSpineItem, ManifestTocNode } from './engine/types';
import { countToc, sameChapter } from './toc';

interface PanelShellProps {
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
}

function PanelShell({ title, children, footer, onClose }: PanelShellProps) {
  return <aside className="reader-panel" role="dialog" aria-label={title}>
    <div className="reader-panel-head">
      <h2>{title}</h2>
      <button type="button" className="reader-icon-button" aria-label={`关闭${title}`} onClick={onClose} autoFocus>
        <X size={17} strokeWidth={1.7} aria-hidden="true" />
      </button>
    </div>
    <div className="reader-panel-body">{children}</div>
    {footer && <div className="reader-panel-foot">{footer}</div>}
  </aside>;
}

interface ContentsProps {
  toc: ManifestTocNode[];
  spine: ManifestSpineItem[];
  currentHref: string | null;
  onSelect: (href: string) => void;
  onClose: () => void;
}

function TocNodes({ nodes, depth, currentHref, onSelect, activeRef }: {
  nodes: ManifestTocNode[];
  depth: number;
  currentHref: string | null;
  onSelect: (href: string) => void;
  activeRef: RefObject<HTMLButtonElement | null>;
}) {
  return <ul className="reader-toc">
    {nodes.map((node, index) => {
      const children = node.children ?? [];
      const childMatches = children.some(child => currentHref !== null && sameChapter(child.href, currentHref));
      const current = currentHref !== null && sameChapter(node.href, currentHref) && !childMatches;
      return <li key={`${node.id}-${depth}-${index}`}>
        <button type="button" ref={current ? activeRef : undefined}
          className={`${depth === 0 && children.length > 0 ? 'reader-toc-part' : ''}${current ? ' current' : ''}`}
          style={{ paddingLeft: `${10 + depth * 14}px` }}
          aria-current={current ? 'location' : undefined}
          onClick={() => onSelect(node.href)}>
          <span className="reader-toc-label">{node.label}</span>
          {current && <Check size={14} strokeWidth={2} aria-hidden="true" />}
        </button>
        {children.length > 0 && <TocNodes nodes={children} depth={depth + 1}
          currentHref={currentHref} onSelect={onSelect} activeRef={activeRef} />}
      </li>;
    })}
  </ul>;
}

export function ContentsPanel({ toc, spine, currentHref, onSelect, onClose }: ContentsProps) {
  const activeRef = useRef<HTMLButtonElement | null>(null);
  useEffect(() => {
    const frame = requestAnimationFrame(() => activeRef.current?.scrollIntoView({ block: 'center' }));
    return () => cancelAnimationFrame(frame);
  }, []);
  const hasToc = toc.length > 0;
  const total = spine.length || countToc(toc);
  return <PanelShell title="章节目录" onClose={onClose}
    footer={<><span>共 {total} 章</span><span>点击章节跳转</span></>}>
    {hasToc
      ? <TocNodes nodes={toc} depth={0} currentHref={currentHref} onSelect={onSelect} activeRef={activeRef} />
      : <ul className="reader-toc">{spine.map((item, index) => {
          const current = currentHref !== null && sameChapter(item.href, currentHref);
          return <li key={item.chapterId}>
            <button type="button" ref={current ? activeRef : undefined}
              className={current ? 'current' : ''} aria-current={current ? 'location' : undefined}
              onClick={() => onSelect(item.href)}>
              <span className="reader-toc-index">{String(index + 1).padStart(2, '0')}</span>
              <span className="reader-toc-label">{item.title}</span>
              {current && <Check size={14} strokeWidth={2} aria-hidden="true" />}
            </button>
          </li>;
        })}
      </ul>}
  </PanelShell>;
}

const FONTS = [
  { value: 'Georgia', label: 'Georgia' },
  { value: 'Palatino Linotype', label: 'Palatino' },
  { value: 'Arial', label: 'Arial' },
];
const SIZES = [
  { value: 18, label: '小' }, { value: 20, label: '中' }, { value: 22, label: '大' }, { value: 24, label: '特大' },
];
const LEADINGS = [
  { value: 1.6, label: '紧凑' }, { value: 1.8, label: '标准' }, { value: 2, label: '宽松' },
];
const WIDTHS = [
  { value: 660, label: '标准宽度' }, { value: 720, label: '宽松宽度' },
];
const THEMES = [
  { value: 'white', label: '清白' }, { value: 'warm', label: '暖纸' }, { value: 'dark', label: '夜读' },
];

interface SettingsProps {
  draft: Preferences;
  state: SaveState;
  onEdit: (preferences: Preferences) => void;
  onRetry: () => void;
  onResolve: (keepDraft: boolean) => void;
  onClose: () => void;
}

export function SettingsPanel({ draft, state, onEdit, onRetry, onResolve, onClose }: SettingsProps) {
  const edit = (patch: Partial<Preferences>) => onEdit({ ...draft, ...patch });
  const status = ({ loading: '读取中', saved: '已同步', saving: '保存中', unsynced: '未同步', conflict: '需处理' })[state.status];
  return <PanelShell title="阅读设置" onClose={onClose}
    footer={<><span>偏好自动保存 · {status}</span>{state.status === 'unsynced' && <button type="button" className="reader-text-button" onClick={onRetry}><RefreshCw size={12} aria-hidden="true" />重试</button>}</>}>
    {state.status === 'conflict' && <div className="reader-panel-alert" role="alert">
      <p>{state.error ?? '设置已在其他页面更新。'}</p>
      <div className="reader-panel-alert-actions">
        <button type="button" className="reader-choice" onClick={() => onResolve(false)}>使用服务器设置</button>
        <button type="button" className="reader-choice" onClick={() => onResolve(true)}>保留当前修改</button>
      </div>
    </div>}
    <fieldset className="reader-setting"><legend>字体</legend>
      <div className="reader-choice-row">{FONTS.map(font => <button key={font.value} type="button"
        className="reader-choice" style={{ fontFamily: font.value }} aria-pressed={draft.font === font.value}
        onClick={() => edit({ font: font.value })}>{font.label}</button>)}</div>
    </fieldset>
    <fieldset className="reader-setting"><legend>字号</legend>
      <div className="reader-choice-row">{SIZES.map(size => <button key={size.value} type="button"
        className="reader-choice" aria-pressed={draft.fontSize === size.value}
        onClick={() => edit({ fontSize: size.value })}>{size.label}</button>)}</div>
    </fieldset>
    <fieldset className="reader-setting"><legend>行间距</legend>
      <div className="reader-choice-row">{LEADINGS.map(leading => <button key={leading.value} type="button"
        className="reader-choice" aria-pressed={draft.lineHeight === leading.value}
        onClick={() => edit({ lineHeight: leading.value })}>{leading.label}</button>)}</div>
    </fieldset>
    <fieldset className="reader-setting"><legend>正文宽度</legend>
      <div className="reader-choice-row">{WIDTHS.map(width => <button key={width.value} type="button"
        className="reader-choice" aria-pressed={draft.width === width.value}
        onClick={() => edit({ width: width.value })}>{width.label}</button>)}</div>
    </fieldset>
    <fieldset className="reader-setting"><legend>背景</legend>
      <div className="reader-choice-row">{THEMES.map(theme => <button key={theme.value} type="button"
        className={`reader-choice reader-theme-choice theme-${theme.value}`} aria-pressed={draft.theme === theme.value}
        onClick={() => edit({ theme: theme.value })}><span className="reader-theme-dot" aria-hidden="true" />{theme.label}</button>)}</div>
    </fieldset>
  </PanelShell>;
}
