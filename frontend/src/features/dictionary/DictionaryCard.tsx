import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { ChevronDown, Loader2, Volume2, X } from 'lucide-react';
import { placeCard } from '../reader/preview/position';
import type { TextSelection } from '../reader/engine/selection';
import type { LookupRegion, LookupViewState } from './model';
import { activeLemma, candidateGroups, groupSenses } from './sense-groups';
import { SenseGroups } from './SenseGroups';
import { FavoriteButton } from '../favorites/FavoriteButton';
import '../favorites/favorites.css';
import type { PlaybackState } from './PronunciationPlayer';

interface Props {
  bookId: string;
  selection: TextSelection;
  state: LookupViewState;
  activeRegion: number;
  playback: PlaybackState;
  onRegion: (index: number) => void;
  onRetry: () => void;
  onPlay: (audioId: string) => void;
  onClose: () => void;
}

function contextText(selection: TextSelection) {
  const source = selection.source;
  const before = source.sentence.slice(0, source.wordStart);
  const word = source.sentence.slice(source.wordStart, source.wordEnd);
  const after = source.sentence.slice(source.wordEnd);
  return { before, word, after };
}

function accentLabel(accent: string, word: string): string {
  const value = accent.toLowerCase();
  if (value === 'en-gb' || value === 'uk' || value === 'gb') return `${word} 英音`;
  if (value === 'en-us' || value === 'us') return `${word} 美音`;
  return `${word} 发音`;
}

function sameWord(a: string, b: string): boolean {
  return a.normalize('NFC').toLowerCase() === b.normalize('NFC').toLowerCase();
}

function displayLemma(region: LookupRegion | undefined, surface: string): string {
  return region?.lemma || region?.headword || surface;
}

export function DictionaryCard({ bookId, selection, state, activeRegion, playback, onRegion, onRetry, onPlay, onClose }: Props) {
  const cardRef = useRef<HTMLElement | null>(null);
  const [style, setStyle] = useState<CSSProperties>({ visibility: 'hidden' });
  const [expanded, setExpanded] = useState(false);

  useEffect(() => { setExpanded(false); }, [selection]);

  // One position per selection. Expanding, changing candidates or favorite state must never
  // remeasure: the card grows inside its fixed top/left and bounded scroll area.
  useLayoutEffect(() => {
    const card = cardRef.current;
    if (!card) return;
    const rect = card.getBoundingClientRect();
    const placement = placeCard(selection.rect, { width: rect.width, height: rect.height },
      { width: window.innerWidth, height: window.innerHeight });
    setStyle({ left: placement.left, top: placement.top, maxHeight: placement.maxHeight, visibility: 'visible' });
  }, [selection]);

  const data = state.data;
  const regions = data ? (data.regions.length ? data.regions : data.entry ? [data.entry] : []) : [];
  const region = activeLemma(regions, activeRegion) ?? data?.entry ?? undefined;
  const lemma = displayLemma(region, selection.source.surface);
  const phonetic = region?.phonetics.find(item => item.ipa)?.ipa;
  const groups = groupSenses(regions, activeRegion);
  const visibleGroups = groups.slice(0, 3);
  const moreGroups = groups.slice(3);
  const candidates = candidateGroups(regions);
  const selectedLemmaKey = region?.lemmaKey;
  const audio = region?.pronunciations;
  const context = contextText(selection);
  const playable = audio?.items ?? [];
  const primarySenseId = groups[0]?.senseId;
  const showSurface = !sameWord(selection.source.surface, lemma);

  return <section ref={cardRef} className="dictionary-preview dictionary-card" style={style}
    role="dialog" aria-label={region ? `查询 ${lemma}` : '查词'} data-testid="dictionary-card">
    <div className="dictionary-preview-head">
      <div>
        <span className="dictionary-preview-badge">COL 词典</span>
        <div className="dictionary-word-row">
          <h2>{lemma}</h2>
          {phonetic && <span className="dictionary-inline-ipa">{phonetic}</span>}
        </div>
        {showSurface && <p className="dictionary-meta"><span>原文词形：{selection.source.surface}</span></p>}
      </div>
      <button type="button" className="reader-icon-button" aria-label="关闭词典卡片" onClick={onClose}>
        <X size={16} aria-hidden="true" />
      </button>
    </div>

    {state.phase === 'loading' && <div className="dictionary-loading" role="status">
      <Loader2 size={16} className="dictionary-spin" aria-hidden="true" />正在查询…
    </div>}

    {(state.phase === 'error' || data?.status === 'NOT_FOUND' || data?.status === 'UNSUPPORTED_QUERY') &&
      <FavoriteButton key={`${selection.source.wordRangeCfi}:state-source`} bookId={bookId} source={selection.source} lemma={selection.source.surface} />}

    {state.phase === 'error' && <div className="dictionary-error" role="alert">
      <p>{state.message ?? '查词暂时不可用。'}</p>
      {state.retryable && <button type="button" className="dictionary-action primary" onClick={onRetry}>重试查询</button>}
    </div>}

    {state.phase === 'ready' && data && <>
      {data.status === 'NOT_FOUND' && <div className="dictionary-empty">
        <p>未收录「{data.source.surface}」。</p>
        <span>原文仍可继续阅读，换一个词试试。</span>
      </div>}
      {data.status === 'UNSUPPORTED_QUERY' && <div className="dictionary-empty">
        <p>暂不支持这个查询。</p>
        <span>请选择英文单词后重试。</span>
      </div>}
      {(data.status === 'FOUND' || data.status === 'AMBIGUOUS') && region && <>
        {candidates.length > 1 && <div className="dictionary-candidates" aria-label="词条候选">
          {candidates.map(candidate => <button key={candidate.key} type="button"
            className={`dictionary-candidate${candidate.key === selectedLemmaKey ? ' active' : ''}`}
            aria-pressed={candidate.key === selectedLemmaKey} onClick={() => onRegion(candidate.index)}>
            {candidate.label}
          </button>)}
        </div>}

        <SenseGroups groups={visibleGroups} />
        {moreGroups.length > 0 && <div className="dictionary-more">
          <button type="button" className="dictionary-more-button" aria-expanded={expanded}
            onClick={() => setExpanded(value => !value)}>
            {expanded ? '收起更多释义' : `更多释义（${moreGroups.length}）`}
            <ChevronDown size={14} className={expanded ? 'dictionary-rotate' : ''} aria-hidden="true" />
          </button>
          {expanded && <SenseGroups groups={moreGroups} />}
        </div>}

        <div className="dictionary-context">
          <span className="dictionary-context-caption">原文语境</span>
          <p lang="en">{context.before}<mark>{context.word}</mark>{context.after}</p>
        </div>

        <div className="dictionary-audio" aria-label="发音">
          {data.audioError ? <span>音频暂不可用</span>
            : audio?.status === 'notConfigured' ? <span>音频未配置</span>
            : audio?.status === 'missing' || playable.length === 0 ? <span>暂无录音</span>
            : playable.map(item => {
              const active = playback.audioId === item.audioId && (playback.status === 'loading' || playback.status === 'playing');
              return <button key={item.audioId} type="button" className={`dictionary-audio-button${active ? ' active' : ''}`}
                onClick={() => onPlay(item.audioId)} title={accentLabel(item.accent, audio?.word ?? lemma)}>
                {active ? <Loader2 size={14} className="dictionary-spin" aria-hidden="true" /> : <Volume2 size={14} aria-hidden="true" />}
                {accentLabel(item.accent, audio?.word ?? lemma)}
              </button>;
            })}
          {playback.status === 'error' && playback.audioId && <span>播放失败，请重试</span>}
        </div>

        <div className="dictionary-actions">
          {data.lookupId && primarySenseId
            ? <FavoriteButton key={`${data.lookupId}:${primarySenseId}`} lookupId={data.lookupId} senseId={primarySenseId}
                source={data.source} lemma={lemma} bookId={bookId} />
            : <span>词条未确认，暂不可收藏</span>}
        </div>
      </>}
    </>}
  </section>;
}
