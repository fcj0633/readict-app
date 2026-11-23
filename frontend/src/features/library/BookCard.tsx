import type { Book } from './model';
import { bookStatusLabel, canOpen, canRetry, coverInitial, formatDate } from './model';

interface Props {
  book: Book;
  onOpen: (book: Book) => void;
  onDelete: (book: Book) => void;
  onRetry: (book: Book) => void;
}

export default function BookCard({ book, onOpen, onDelete, onRetry }: Props) {
  const ready = canOpen(book.status);
  return <article className="shelf-card">
    <div className="shelf-cover" aria-hidden="true">
      {book.coverUrl
        ? <img src={book.coverUrl} alt="" onError={event => { (event.currentTarget as HTMLImageElement).style.display = 'none'; }} />
        : <span>{coverInitial(book.title)}</span>}
    </div>
    <div className="shelf-meta">
      <h2>{book.title}</h2>
      <p className="shelf-author">{book.author ?? '作者未知'}</p>
      <p className={`shelf-status shelf-status-${book.status.toLowerCase()}`}>{bookStatusLabel(book.status)}</p>
      <p className="shelf-date">最近阅读 {formatDate(book.lastReadAt)}</p>
      {book.progressRatio !== null && <p className="shelf-progress">已读 {Math.round(book.progressRatio * 100)}%</p>}
      <div className="shelf-actions">
        <button type="button" onClick={() => onOpen(book)} disabled={!ready}
          title={ready ? '阅读器尚未接入，M04 开放' : '书籍尚不可阅读'}>打开</button>
        {canRetry(book.status) && <button type="button" className="secondary-button" onClick={() => onRetry(book)}>重新导入</button>}
        <button type="button" className="link-button" onClick={() => onDelete(book)}>删除</button>
      </div>
    </div>
  </article>;
}
