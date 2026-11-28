import { Link } from 'react-router-dom';
export function Brand({ compact = false }: { compact?: boolean }) {
  return <Link to="/books" className={compact ? 'app-brand compact' : 'app-brand'} aria-label="Readict 书架"><span className="app-brand-mark" aria-hidden="true">r</span><span>Readict</span></Link>;
}
