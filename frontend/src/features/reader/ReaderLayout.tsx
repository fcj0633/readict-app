import { Link } from 'react-router-dom';

export function ReaderHeader({ title, onToc, onSettings }: { title: string; onToc: () => void; onSettings: () => void }) {
  return <header className="reader-header">
    <Link to="/books" className="reader-back" aria-label="返回书架">← 书架</Link>
    <h1 title={title}>{title}</h1>
    <div className="reader-header-actions">
      <button type="button" onClick={onToc}>目录</button>
      <button type="button" onClick={onSettings}>设置</button>
    </div>
  </header>;
}

export function ReaderRail({ onToc, onFavorites, onSettings }: {
  onToc: () => void; onFavorites: () => void; onSettings: () => void;
}) {
  return <nav className="reader-rail" aria-label="阅读工具">
    <button type="button" onClick={onToc} title="章节目录">目录</button>
    <button type="button" onClick={onFavorites} disabled title="收藏将在 M06 接入">收藏</button>
    <button type="button" onClick={onSettings} title="阅读设置">设置</button>
  </nav>;
}
