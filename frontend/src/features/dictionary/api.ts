import { request } from '../../lib/http';
import { decodeLookup, type LookupResult } from './model';
import type { SelectionSource } from '../reader/engine/selection';

export interface LookupRequest {
  bookId: string;
  source: SelectionSource;
  posHint?: string;
  lemmaCandidate?: string;
}

export function lookupWord(requestBody: LookupRequest, signal?: AbortSignal): Promise<LookupResult> {
  return request('/dictionary/lookups', decodeLookup, {
    method: 'POST',
    body: requestBody,
    signal,
  });
}
