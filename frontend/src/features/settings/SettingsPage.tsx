import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { PreferenceSaver, type SaveState, type Preferences } from './model';

export default function SettingsPage() {
  const auth = useAuth(); const saver = useRef<PreferenceSaver | undefined>(undefined); const [state, setState] = useState<SaveState>({ status: 'loading' });
  useEffect(() => { const instance = new PreferenceSaver(setState, undefined, view => { auth.announce('settings'); window.dispatchEvent(new CustomEvent('readict-settings-saved', { detail: view.preferences.theme })); }); saver.current = instance; void instance.load(); return () => { instance.dispose(); window.dispatchEvent(new Event('readict-preview-ended')); }; }, [auth.user?.id]);
  const previousRevision = useRef(auth.revision);
  useEffect(() => { if (previousRevision.current !== auth.revision) { previousRevision.current = auth.revision; void saver.current?.load(); } }, [auth.revision]);
  useEffect(() => { if (state.draft) document.documentElement.dataset.theme = state.draft.theme; }, [state.draft?.theme]);
  function edit<K extends keyof Preferences>(key: K, value: Preferences[K]) { if (state.draft) saver.current?.edit({ ...state.draft, [key]: value }); }
  const p = state.draft;
  return <section className="account-content"><div className="page-heading"><div><p className="section-intro">个人偏好</p><h1>让阅读更合心意。</h1></div><p className="save-status" role="status">{({ loading: '正在读取…', saved: '已同步', saving: '正在保存…', unsynced: '尚未同步', conflict: '需要处理冲突' })[state.status]}</p></div>
    {state.error && <p className="form-error" role="alert">{state.error}</p>}{state.status === 'unsynced' && <button className="secondary-button" onClick={() => saver.current?.retry()}>重新同步</button>}
    {state.status === 'conflict' && <div className="conflict-actions"><button onClick={() => saver.current?.resolve(false)}>加载服务器设置</button><button onClick={() => saver.current?.resolve(true)}>保留草稿并重新保存</button></div>}
    {p && <div className="settings-layout"><div className="settings-controls">
      <label htmlFor="font">字体</label><select id="font" value={p.font} onChange={e => edit('font', e.target.value)}><option>Georgia</option><option>Palatino Linotype</option><option>Arial</option></select>
      <label htmlFor="font-size">字号</label><select id="font-size" value={p.fontSize} onChange={e => edit('fontSize', Number(e.target.value))}>{[18, 20, 22, 24].map(v => <option key={v}>{v}</option>)}</select>
      <label htmlFor="line-height">行距</label><select id="line-height" value={p.lineHeight} onChange={e => edit('lineHeight', Number(e.target.value))}>{[1.6, 1.8, 2].map(v => <option key={v}>{v}</option>)}</select>
      <label htmlFor="width">内容宽度</label><select id="width" value={p.width} onChange={e => edit('width', Number(e.target.value))}><option value="660">适中</option><option value="720">宽松</option></select>
      <label htmlFor="theme">主题</label><select id="theme" value={p.theme} onChange={e => edit('theme', e.target.value)}><option value="white">清白</option><option value="warm">暖纸</option><option value="dark">夜读</option></select>
      <label htmlFor="pronunciation">发音偏好</label><select id="pronunciation" value={p.pronunciation} onChange={e => edit('pronunciation', e.target.value)}><option value="en-GB">英式英语</option><option value="en-US">美式英语</option></select><p className="field-hint">仅保存偏好，音频功能尚未接入。</p>
    </div><div className="reading-preview"><p className="section-intro">效果预览</p><article style={{ fontFamily: `${p.font}, ${p.font === 'Arial' ? 'sans-serif' : 'serif'}`, fontSize: p.fontSize, lineHeight: p.lineHeight, maxWidth: p.width }}><h2>A quiet moment</h2><p>There is a pleasure in opening a book and finding a sentence that stays with you. Read slowly. Let the words make room for a new thought.</p></article><p className="field-hint">预览随设置即时变化，修改会自动同步到你的账号。</p></div></div>}
  </section>;
}
