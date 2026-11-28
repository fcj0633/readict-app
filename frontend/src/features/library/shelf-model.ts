import type { Book, Shelf } from './model';
export function progressPercent(value: number | null): number | null { return value !== null && Number.isFinite(value) && value >= 0 && value <= 1 ? Math.round(value * 100) : null; }
export function coverPath(book: Book): string | null { return book.coverUrl === '/api/v1/books/' + book.id + '/cover' ? book.coverUrl : null; }
export function appendPage(items: Book[], page: Shelf, cursor: string | null): Book[] {
 const ids=new Set(items.map(b=>b.id)),added=page.items.filter(b=>{if(ids.has(b.id))return false;ids.add(b.id);return true;});
 if(page.nextCursor&&(page.nextCursor===cursor||(items.length>0&&added.length===0)))throw Error('书架分页暂时无法继续，请刷新重试');
 return [...items,...added];
}
export async function findContinue(fetch:(cursor:string|null)=>Promise<Shelf>,signal:AbortSignal):Promise<Book|null>{
 const cursors=new Set<string>(),ids=new Set<string>();let cursor:string|null=null,first:Book|null=null;
 do{const page=await fetch(cursor);if(signal.aborted)return null;let added=0;
 for(const book of page.items){if(ids.has(book.id))continue;ids.add(book.id);added++;if(book.status!=='READY')continue;first??=book;if(book.progressRatio!==null)return book;}
 if(page.nextCursor&&(cursors.has(page.nextCursor)||added===0))throw Error('继续阅读加载中断，请重试');
 cursor=page.nextCursor;if(cursor)cursors.add(cursor);
 }while(cursor);return first;
}
