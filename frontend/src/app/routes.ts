export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const readerPath = (id: string) => { if (!UUID.test(id)) throw new Error('无效书籍编号'); return '/reader/' + id; };
export function parseReaderPath(raw: string): string | null {
  const match = /^\/reader\/([^/?#]+)$/.exec(raw);
  return match && UUID.test(match[1]!) ? raw : null;
}
export function safeReturnTo(raw: string | null): string {
  if (!raw || (raw.includes('\\') || raw.includes('#') || [...raw].some(c => c.charCodeAt(0) <= 32 || c.charCodeAt(0) === 127)) || !raw.startsWith('/') || raw.startsWith('//')) return '/books';
  const path = raw.split('?')[0]!;
  if (path.includes('%') || path.includes('//') || path.split('/').some(p => p === '.' || p === '..')) return '/books';
  const url = new URL(raw, 'https://readict.invalid');
  if (url.origin !== 'https://readict.invalid' || url.pathname !== path) return '/books';
  if (['/settings', '/settings/pronunciation', '/settings/reading'].includes(path)) return '/settings/reading';
  if (['/books', '/account'].includes(path)) return path;
  if (path === '/favorites') {
    const from = url.searchParams.get('from');
    return from && url.searchParams.getAll('from').length === 1 && parseReaderPath(from)
      ? '/favorites?' + new URLSearchParams({ from }) : '/favorites';
  }
  if (parseReaderPath(path)) {
    const favorite = url.searchParams.get('favorite'), context = url.searchParams.get('context');
    return favorite && context && UUID.test(favorite) && UUID.test(context)
      && url.searchParams.getAll('favorite').length === 1 && url.searchParams.getAll('context').length === 1
      ? path + '?' + new URLSearchParams({ favorite, context }) : path;
  }
  return '/books';
}
export const loginHref = (location: { pathname: string; search: string }) =>
  '/login?' + new URLSearchParams({ returnTo: safeReturnTo(location.pathname + location.search) });
export const authHref = (path: string, returnTo: string | null) => path + '?' + new URLSearchParams({ returnTo: safeReturnTo(returnTo) });
