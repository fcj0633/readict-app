import type { ReaderPreferences } from './types';

interface Palette { paper: string; ink: string; accent: string; selection: string }

const PALETTES: Record<ReaderPreferences['theme'], Palette> = {
  white: { paper: '#fffefb', ink: '#302e2b', accent: '#405b4c', selection: '#dce5d6' },
  warm: { paper: '#fffdf9', ink: '#302e2b', accent: '#405b4c', selection: '#e4dccd' },
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
      'text-align': 'left',
      hyphens: 'auto',
      '-webkit-hyphens': 'auto',
      'overflow-wrap': 'break-word',
      'text-rendering': 'optimizeLegibility',
    },
    'p': { margin: '0 0 1em 0', 'text-indent': '0' },
    'h1, h2, h3, h4, h5, h6': {
      'font-family': family,
      'font-weight': '600',
      'line-height': '1.28',
      'margin': '0 0 1.1em 0',
      'letter-spacing': '-0.005em',
    },
    'h1': { 'font-size': '1.5em' },
    'h2': { 'font-size': '1.28em' },
    'h3': { 'font-size': '1.12em' },
    'blockquote': { margin: '1em 0', 'padding-left': '1em', 'border-left': '2px solid ' + palette.selection },
    'img': { 'max-width': '100%', height: 'auto' },
    'a': { color: palette.accent },
    'pre': { 'white-space': 'pre-wrap' },
    '::selection': { background: palette.selection },
    '*': { 'box-sizing': 'border-box' },
  };
}

export function widthStyle(preferences: ReaderPreferences): { maxWidth: string } {
  return { maxWidth: `${preferences.width}px` };
}
