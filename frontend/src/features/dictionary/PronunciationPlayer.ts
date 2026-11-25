export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'ended' | 'error';

export interface PlaybackState {
  audioId: string | null;
  status: PlaybackStatus;
}

/** One lazy Audio instance shared by every word card; only a click sets src and starts playback. */
export class PronunciationPlayer {
  private audio: HTMLAudioElement | null = null;
  private generation = 0;
  private state: PlaybackState = { audioId: null, status: 'idle' };
  private notify: ((state: PlaybackState) => void) | undefined;

  constructor(private readonly emit: (state: PlaybackState) => void) {}

  play(audioId: string, onState?: (state: PlaybackState) => void): void {
    if (this.state.audioId === audioId && (this.state.status === 'loading' || this.state.status === 'playing')) {
      this.stop();
      return;
    }
    const generation = ++this.generation;
    const audio = this.ensure();
    this.bind(audio);
    this.notify = onState;
    this.setState({ audioId, status: 'loading' }, onState);
    audio.src = `/api/v1/dictionary/audio/${encodeURIComponent(audioId)}`;
    const promise = audio.play();
    if (promise) {
      promise.then(() => {
        if (generation !== this.generation) return;
        this.setState({ audioId, status: 'playing' }, onState);
      }).catch(() => {
        if (generation !== this.generation) return;
        this.setState({ audioId, status: 'error' }, onState);
      });
    }
  }

  stop(): void {
    this.generation++;
    const audio = this.audio;
    if (audio) {
      audio.pause();
      audio.removeAttribute('src');
      try { audio.load(); } catch { /* browser already tearing down */ }
    }
    this.setState({ audioId: null, status: 'idle' });
  }

  dispose(): void {
    this.generation++;
    this.stop();
    this.audio = null;
  }

  private ensure(): HTMLAudioElement {
    if (!this.audio) {
      const audio = new Audio();
      audio.preload = 'none';
      this.audio = audio;
    }
    return this.audio;
  }

  private bind(audio: HTMLAudioElement): void {
    if (audio.dataset.readictBound === '1') return;
    audio.dataset.readictBound = '1';
    audio.addEventListener('ended', () => {
      if (this.audio !== audio || !this.state.audioId) return;
      this.setState({ audioId: this.state.audioId, status: 'ended' });
    });
    audio.addEventListener('error', () => {
      if (this.audio !== audio || !this.state.audioId) return;
      this.setState({ audioId: this.state.audioId, status: 'error' });
    });
  }

  private setState(state: PlaybackState, onState?: (state: PlaybackState) => void): void {
    this.state = state;
    this.emit(state);
    this.notify?.(state);
    onState?.(state);
  }
}
