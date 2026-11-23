import type { ReaderPreferences } from './types';

interface Palette { paper: string; ink: string; accent: string; selection: string }

const PALETTES: Record<ReaderPreferences['theme'], Palette> = {
  white: { paper: '#fffdfa', ink: '#302e2b', accent: '#405b4c', selection: '#dce5d6' },
  warm: { paper: '#f5efe4', ink: '#302e2b', accent: '#405b4c', selection: '#e4dccd' },
  dark: { paper: '#1f1e1c', ink: '#d8d2c7', accent: '#9fb8a6', selection: '#3a4038' },
};

export function themeRules(preferences: ReaderPreferences): Record<string, Record<string, string>> {
  const palette = PALETTES[preferences.theme] ?? PALETTES.warm;
  const family = preferences.font === 'Arial' ? 'Arial, sans-serif'
    : `${preferences.font}, "Palatino Linotype", Georgia, serif`;
  return {
    'html': { background: palette.paper, color: palette.ink },
    'body': {
      'font-family': family,
      'font-size': `${preferences.fontSize}px`,
      'line-height': String(preferences.lineHeight),
      color: palette.ink,
      background: palette.paper,
      padding: '0',
      margin: '0',
    },
    'p': { margin: '0 0 1em 0' },
    'img': { 'max-width': '100%', height: 'auto' },
    'a': { color: palette.accent },
    '::selection': { background: palette.selection },
  };
}

export function widthStyle(preferences: ReaderPreferences): { maxWidth: string } {
  return { maxWidth: `${preferences.width}px` };
}
