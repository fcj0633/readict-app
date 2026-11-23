import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { Bookmark, Check, Languages, Volume2, X } from 'lucide-react';
import { previewEntry } from './fixture';
import { placeCard, type AnchorRect } from './position';

export function DictionaryPreviewCard({ anchor, onClose }: { anchor: AnchorRect; onClose: () => void }) {
  const cardRef = useRef<HTMLElement | null>(null);
  const [style, setStyle] = useState<CSSProperties>({ visibility: 'hidden' });
  const [translated, setTranslated] = useState(false);
  const [spoken, setSpoken] = useState(false);

  useLayoutEffect(() => {
    const measure = () => {
      const card = cardRef.current;
      if (!card) return;
      const rect = card.getBoundingClientRect();
      const next = placeCard(anchor, { width: rect.width, height: rect.height },
        { width: window.innerWidth, height: window.innerHeight });
      setStyle({ left: next.left, top: next.top, maxHeight: next.maxHeight, visibility: 'visible' });
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [anchor, translated]);

  useEffect(() => {
    const timer = window.setTimeout(() => setSpoken(false), 2000);
    return () => window.clearTimeout(timer);
  }, [spoken]);

  function speak() {
    if (!('speechSynthesis' in window)) return;
    const utterance = new SpeechSynthesisUtterance(previewEntry.word);
    utterance.lang = 'en-GB';
    utterance.rate = 0.85;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
    setSpoken(true);
  }

  return <section ref={cardRef} className="dictionary-preview" style={style}
    role="dialog" aria-label={`查询 ${previewEntry.word}`} data-testid="dictionary-preview">
    <div className="dictionary-preview-head">
      <div>
        <span className="dictionary-preview-badge">视觉预览 · M05 未接入</span>
        <div className="dictionary-word-row">
          <h2>{previewEntry.word}</h2>
          <button type="button" className="reader-icon-button" aria-label="播放发音" onClick={speak}>
            {spoken ? <Check size={16} aria-hidden="true" /> : <Volume2 size={16} aria-hidden="true" />}
          </button>
        </div>
        <p className="dictionary-meta"><span>{previewEntry.ipa}</span><span>原文词形：{previewEntry.form}</span></p>
      </div>
      <button type="button" className="reader-icon-button" aria-label="关闭词典卡片" onClick={onClose}>
        <X size={16} aria-hidden="true" />
      </button>
    </div>

    <div className="dictionary-senses">
      {previewEntry.senses.map(sense => <div className="dictionary-sense" key={sense.meaning}>
        <span>{sense.pos}</span>
        <p>{sense.meaning}</p>
      </div>)}
    </div>

    <div className="dictionary-context">
      <span className="dictionary-context-caption">原文语境</span>
      <p lang="en">{previewEntry.context}</p>
      {translated && <p className="dictionary-translation" lang="zh">{previewEntry.translation}</p>}
    </div>

    <div className="dictionary-actions">
      <button type="button" className="dictionary-action primary" onClick={() => setTranslated(true)} disabled={translated}>
        <Languages size={15} aria-hidden="true" />{translated ? '已翻译' : '翻译例句'}
      </button>
      <button type="button" className="dictionary-action" disabled title="收藏将在 M06 接入">
        <Bookmark size={15} aria-hidden="true" />收藏（M06）
      </button>
    </div>
  </section>;
}
