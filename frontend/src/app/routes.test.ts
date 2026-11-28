import {test} from 'node:test';
import assert from 'node:assert/strict';
import {safeReturnTo,parseReaderPath,loginHref} from './routes';
const r='/reader/a0000000-0000-4000-8000-000000000001';
test('safe routes preserve only scoped canonical deep links',()=>{
 for(const path of ['/books','/favorites','/account','/settings/reading',r])assert.equal(safeReturnTo(path),path);
 assert.equal(safeReturnTo('/settings'),'/settings/reading');
 assert.equal(safeReturnTo(r+'?favorite=a0000000-0000-4000-8000-000000000002&context=a0000000-0000-4000-8000-000000000003'),r);
 assert.equal(safeReturnTo(r+'?favorite=x'),r);
 assert.equal(safeReturnTo('/favorites?from='+encodeURIComponent(r)),'/favorites?from='+encodeURIComponent(r));
 assert.equal(loginHref({pathname:r,search:'?favorite=x'}),'/login?returnTo='+encodeURIComponent(r));
});
test('unsafe paths and queries cannot escape route whitelist',()=>{
 for(const raw of ['https://evil.test','//evil.test','javascript:alert(1)','/books/../account','/books//x','/reader/no','/login','/%2f/books','/%252f/books','/books\\evil','/books#fragment','/books\n']) assert.equal(safeReturnTo(raw),'/books',raw);
 assert.equal(safeReturnTo('/favorites?from='+encodeURIComponent('//evil.test')),'/favorites');
 assert.equal(safeReturnTo('/favorites?from='+encodeURIComponent(r)+'&from='+encodeURIComponent(r)),'/favorites');
 assert.equal(safeReturnTo(r+'?favorite=x'),r);
 assert.equal(parseReaderPath('/reader/'+'a'.repeat(36)),null);
});
