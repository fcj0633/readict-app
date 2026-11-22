export const subsetPolicy='audio-local-subset-v1';
export const fullPolicy='audio-full-v1';
export const approvalRef='user-2026-11-22-allow-defective-recordings';
// Environment failures, timeouts and unknown faults remain blocking.
export const quarantinable=new Set(['AUDIO_SOURCE_SIGNAL','AUDIO_OUTPUT_QUALITY','AUDIO_SOURCE_FORMAT','AUDIO_SOURCE_DECODE','AUDIO_PCM_INVALID']);
