import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { NO_SORT, optionalParam, readSort, sortQueryParams } from './view-query-params';

describe('readSort', () => {
  it('reads a column and its direction from the URL', () => {
    expect(readSort(convertToParamMap({ sort: 'total', dir: 'desc' }))).toEqual({ active: 'total', direction: 'desc' });
  });

  it('falls back to no sort when the URL carries none', () => {
    expect(readSort(convertToParamMap({}))).toEqual(NO_SORT);
  });

  it('ignores a direction that is neither asc nor desc, rather than sorting on garbage', () => {
    expect(readSort(convertToParamMap({ sort: 'total', dir: 'sideways' }))).toEqual(NO_SORT);
  });

  it('ignores a column with no direction: half a sort is not a sort', () => {
    expect(readSort(convertToParamMap({ sort: 'total' }))).toEqual(NO_SORT);
  });

  it('keeps an unknown column: the comparator answers "equal" and the table stays in source order', () => {
    // A bookmarked link outliving the column it named must not fail the page.
    expect(readSort(convertToParamMap({ sort: 'colonne-supprimee', dir: 'asc' }))).toEqual({
      active: 'colonne-supprimee',
      direction: 'asc'
    });
  });
});

describe('sortQueryParams', () => {
  it('writes the column and its direction', () => {
    expect(sortQueryParams({ active: 'total', direction: 'asc' })).toEqual({ sort: 'total', dir: 'asc' });
  });

  it('clears both params when the table is unsorted, instead of leaving a stale column behind', () => {
    expect(sortQueryParams(NO_SORT)).toEqual({ sort: null, dir: null });
    expect(sortQueryParams({ active: 'total', direction: '' })).toEqual({ sort: null, dir: null });
  });
});

describe('optionalParam', () => {
  it('keeps a filled value', () => {
    expect(optionalParam('durand')).toBe('durand');
  });

  it('drops an empty or blank value, so a cleared filter leaves the URL', () => {
    expect(optionalParam('')).toBeNull();
    expect(optionalParam('   ')).toBeNull();
    expect(optionalParam(null)).toBeNull();
    expect(optionalParam(undefined)).toBeNull();
  });
});
