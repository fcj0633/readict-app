import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PreferenceSaver, decode, type Preferences, type View } from './model';
import { ApiError } from '../../lib/api';
const prefs: Preferences = { font: 'Georgia', fontSize: 20, lineHeight: 1.8, width: 660, theme: 'warm', pronunciation: 'en-GB' };
const settle = () => new Promise(resolve => setImmediate(resolve));
test('validates server preferences and version', () => { assert.deepEqual(decode({ preferences: prefs, version: 1 }), { preferences: prefs, version: 1 }); assert.throws(() => decode({ preferences: { ...prefs, font: '<script>' }, version: 1 })); assert.throws(() => decode({ preferences: { ...prefs, fontSize: '20' }, version: 1 })); });
test('debounces 500ms and never overlaps saves or overwrites newer draft', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const requests: { preferences: Preferences; expectedVersion: number; requestId: string }[] = [];
  const resolve: ((value: View) => void)[] = [];
  const saver = new PreferenceSaver(() => {}, { get: async () => ({ preferences: prefs, version: 1 }), save: async body => { requests.push(body); return new Promise<View>(done => resolve.push(done)); } });
  await saver.load(); saver.edit({ ...prefs, theme: 'dark' }); context.mock.timers.tick(499); assert.equal(requests.length, 0); context.mock.timers.tick(1); assert.equal(requests.length, 1);
  saver.edit({ ...prefs, theme: 'white' }); context.mock.timers.tick(500); assert.equal(requests.length, 1);
  resolve[0]!({ preferences: requests[0]!.preferences, version: 2 }); await settle(); assert.equal(saver.state.draft?.theme, 'white');
  context.mock.timers.tick(500); assert.equal(requests.length, 2); assert.equal(requests[1]?.expectedVersion, 2);
  resolve[1]!({ preferences: requests[1]!.preferences, version: 3 }); await settle(); assert.equal(saver.state.status, 'saved'); saver.dispose();
});
test('unknown network result retries the same request identity and payload', async () => {
  const requests: unknown[] = []; let fail = true;
  const saver = new PreferenceSaver(() => {}, { get: async () => ({ preferences: prefs, version: 1 }), save: async body => { requests.push(body); if (fail) { fail = false; throw new Error('offline'); } return { preferences: body.preferences, version: 2 }; } });
  await saver.load(); saver.edit({ ...prefs, fontSize: 22 }); await saver.flush(); assert.equal(saver.state.status, 'unsynced');
  saver.edit({ ...prefs, fontSize: 24 }); await saver.flush(); assert.deepEqual(requests[0], requests[1]); assert.equal(saver.state.draft?.fontSize, 24); saver.dispose();
});
test('409 preserves draft until user chooses server or resubmits', async () => {
  let get = 0;
  const saver = new PreferenceSaver(() => {}, { get: async () => ({ preferences: { ...prefs, theme: get++ ? 'dark' : 'warm' }, version: get === 1 ? 1 : 2 }), save: async () => { throw new ApiError(409, 'SETTINGS_CONFLICT', 'conflict'); } });
  await saver.load(); saver.edit({ ...prefs, fontSize: 24 }); await saver.flush(); assert.equal(saver.state.status, 'conflict'); assert.equal(saver.state.draft?.fontSize, 24); assert.equal(saver.state.server?.version, 2);
  saver.resolve(false); assert.equal(saver.state.draft?.theme, 'dark'); assert.equal(saver.state.status, 'saved'); saver.dispose();
});
test('disposed requests cannot publish late results', async () => {
  let done: (value: View) => void = () => {}; let emits = 0;
  const saver = new PreferenceSaver(() => emits++, { get: () => new Promise<View>(resolve => { done = resolve; }), save: async body => ({ preferences: body.preferences, version: 2 }) });
  const pending = saver.load(); saver.dispose(); done({ preferences: prefs, version: 1 }); await pending; assert.equal(emits, 0);
});

test('same-version reload preserves pending draft without conflict; committed is separate',async()=>{
 const saver=new PreferenceSaver(()=>{}, {get:async()=>({preferences:{...prefs,pronunciation:'en-US'},version:1}),save:async body=>({preferences:body.preferences,version:2})});
 await saver.load();saver.edit({...saver.state.draft!,theme:'dark'});await saver.load();assert.equal(saver.state.status,'unsynced');assert.equal(saver.state.draft?.theme,'dark');assert.equal(saver.state.committed?.preferences.theme,'warm');await saver.flushAll();assert.equal(saver.state.committed?.preferences.theme,'dark');assert.equal(saver.state.committed?.preferences.pronunciation,'en-US');saver.dispose();
});
test('reload crossing a PUT cannot overwrite a newer committed version',async()=>{
 let finishGet:(v:View)=>void=()=>{},reads=0;const saver=new PreferenceSaver(()=>{}, {get:async()=>{reads++;if(reads===2)return new Promise<View>(r=>{finishGet=r;});return {preferences:prefs,version:reads>2?2:1};},save:async body=>({preferences:body.preferences,version:2})});
 await saver.load();const refresh=saver.load();saver.edit({...prefs,fontSize:24});await saver.flush();finishGet({preferences:prefs,version:1});await refresh;assert.equal(saver.state.committed?.version,2);saver.dispose();
});
test('different server version conflicts and failed conflict refresh never allows blind save',async()=>{
 let reads=0;const saver=new PreferenceSaver(()=>{}, {get:async()=>({preferences:{...prefs,theme:'white'},version:++reads}),save:async()=>{throw new ApiError(409,'SETTINGS_CONFLICT','conflict');}});
 await saver.load();saver.edit({...prefs,fontSize:24});await saver.load();assert.equal(saver.state.status,'conflict');assert.equal(saver.state.draft?.fontSize,24);saver.resolve(false);assert.equal(saver.state.draft?.theme,'white');assert.equal(saver.state.committed?.version,2);saver.dispose();
});

test('failed refresh of clean preferences retries GET instead of a no-op save',async()=>{
 let reads=0,saves=0;const saver=new PreferenceSaver(()=>{}, {get:async()=>{if(++reads===2)throw Error('offline');return {preferences:prefs,version:1};},save:async body=>{saves++;return {preferences:body.preferences,version:2};}});
 await saver.load();await saver.load();assert.equal(saver.state.status,'unsynced');saver.retry();await settle();assert.equal(reads,3);assert.equal(saves,0);assert.equal(saver.state.status,'saved');saver.dispose();
});
