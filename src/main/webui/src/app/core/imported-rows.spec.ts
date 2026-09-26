import { describe, expect, it } from 'vitest';
import { importedIdsParam, keptByImportedIds, readImportedIds } from './imported-rows';

describe('imported rows in the URL', () => {
  it('reads the ids of the param, and no filter from an absent or empty one', () => {
    expect(readImportedIds('s-1, s-2,,s-3')).toEqual(new Set(['s-1', 's-2', 's-3']));
    expect(readImportedIds(null)).toBeNull();
    expect(readImportedIds(' , ')).toBeNull();
  });

  it('writes them back, and clears the param without a filter', () => {
    expect(importedIdsParam(new Set(['12', '14']))).toBe('12,14');
    expect(importedIdsParam(null)).toBeNull();
  });

  it('keeps every row without a filter, and only the named ones with one', () => {
    expect(keptByImportedIds(null, 's-9')).toBe(true);
    expect(keptByImportedIds(new Set(['12']), 12)).toBe(true);
    expect(keptByImportedIds(new Set(['12']), 13)).toBe(false);
  });
});
