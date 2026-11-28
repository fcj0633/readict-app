import { useEffect, useRef, useState } from 'react';
import { favoriteApi } from './api';
import { PronunciationPlayer, type PlaybackState } from '../dictionary/PronunciationPlayer';
import type { Pronunciation } from '../dictionary/model';

const emptyPlayback: PlaybackState = { audioId: null, status: 'idle' };

/** One shared player for the favorite list and its detail dialog. */
export function useFavoriteAudio(preference: string) {
  const [playback, setPlayback] = useState<PlaybackState>(emptyPlayback);
  const [loadingWord, setLoadingWord] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const player = useRef<PronunciationPlayer | null>(null);
  const request = useRef<AbortController | null>(null);
  const currentWord = useRef<string | null>(null);
  const cache = useRef(new Map<string, Pronunciation>());

  useEffect(() => () => {
    request.current?.abort();
    player.current?.dispose();
    player.current = null;
  }, []);

  function playerInstance(): PronunciationPlayer {
    player.current ??= new PronunciationPlayer(setPlayback);
    return player.current;
  }

  function stop() {
    request.current?.abort();
    request.current = null;
    currentWord.current = null;
    setLoadingWord(null);
    player.current?.stop();
  }

  async function play(wordId: string) {
    if (currentWord.current === wordId && (playback.status === 'loading' || playback.status === 'playing')) {
      stop();
      return;
    }
    player.current?.stop();
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    currentWord.current = wordId;
    setLoadingWord(wordId);
    setErrors(value => { const next = { ...value }; delete next[wordId]; return next; });
    try {
      let value = cache.current.get(wordId);
      if (!value) {
        const loaded = await favoriteApi.pronunciations(wordId, controller.signal);
        if (!loaded || controller.signal.aborted || currentWord.current !== wordId) return;
        value = loaded;
        cache.current.set(wordId, loaded);
      }
      if (controller.signal.aborted || currentWord.current !== wordId) return;
      const items = value.items;
      const selected = items.find(item => item.accent.toLowerCase() === preference.toLowerCase()) ?? items[0];
      if (!selected) {
        throw new Error(value.status === 'notConfigured' ? '音频未配置' : value.status === 'missing' ? '暂无录音' : '暂无可播放录音');
      }
      playerInstance().play(selected.audioId, next => {
        if (currentWord.current === wordId) setPlayback(next);
      });
    } catch (caught) {
      if (controller.signal.aborted) return;
      currentWord.current = null;
      setPlayback(emptyPlayback);
      setErrors(value => ({ ...value, [wordId]: caught instanceof Error ? caught.message : '发音暂不可用' }));
    } finally {
      if (currentWord.current === wordId) setLoadingWord(null);
    }
  }

  return { playback, loadingWord, errors, play, stop };
}
