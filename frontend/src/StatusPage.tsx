import { useEffect, useMemo, useState } from 'react';
import { ApiError, createStatusLoader, type SystemStatus } from './lib/api';

export default function StatusPage() {
  const [state, setState] = useState<SystemStatus | ApiError | null>(null);
  const loader = useMemo(() => createStatusLoader(setState), []);
  useEffect(() => { void loader.retry(); return () => loader.dispose(); }, [loader]);
  const ready = state !== null && !(state instanceof ApiError);
  const waiting = state === null;
  return <div className="page">
    <header><a className="brand" href="/" aria-label="Readict 首页"><span className="book" aria-hidden="true">r</span>Readict</a><span className="environment">本机环境</span></header>
    <main>
      <p className="intro">阅读开始之前</p>
      <h1>让每一次阅读，<br/>都有稳妥的开始。</h1>
      <section className="status-panel" aria-labelledby="status-heading" aria-busy={waiting}>
        <div className={`status-mark ${ready ? 'ready' : waiting ? 'waiting' : 'unavailable'}`} aria-hidden="true">{ready ? '✓' : waiting ? '…' : '!'}</div>
        <div className="status-content" role="status" aria-live="polite">
          <h2 id="status-heading">{waiting ? '正在连接服务' : ready ? '服务已就绪' : '服务暂不可用'}</h2>
          <p>{waiting ? '正在检查连接，请稍候。' : ready ? '连接正常，可以开始下一步。' : '暂时无法完成连接，请稍后重试。'}</p>
          {state?.requestId && <p className="request-id">请求编号 <span>{state.requestId}</span></p>}
        </div>
        <button type="button" onClick={() => void loader.retry()}>{waiting ? '重新连接' : '重新检查'}<span aria-hidden="true">↻</span></button>
      </section>
      <p className="note">Readict 的阅读体验正在准备中。</p>
    </main>
    <footer><span>Readict</span><span>在阅读中，遇见新的理解。</span></footer>
  </div>;
}
