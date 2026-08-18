import { describe, expect, it } from 'vitest';
import { readCollapsedGroups, toggleCollapsedGroup, writeCollapsedGroups } from './nav-collapse';

function fakeStorage(initial?: string): Pick<Storage, 'getItem' | 'setItem'> & { value: string | null } {
  return {
    value: initial ?? null,
    getItem(): string | null {
      return this.value;
    },
    setItem(_key: string, value: string): void {
      this.value = value;
    }
  };
}

describe('nav-collapse', () => {
  it('reads an empty set when nothing was stored', () => {
    expect(readCollapsedGroups(fakeStorage())).toEqual(new Set());
  });

  it('reads an empty set when the stored value is not a string array', () => {
    expect(readCollapsedGroups(fakeStorage('{"nope":1}'))).toEqual(new Set());
    expect(readCollapsedGroups(fakeStorage('not json'))).toEqual(new Set());
    expect(readCollapsedGroups(fakeStorage('["views", 3]'))).toEqual(new Set(['views']));
  });

  it('round-trips through storage', () => {
    const storage = fakeStorage();
    writeCollapsedGroups(storage, new Set(['views', 'tools']));
    expect(readCollapsedGroups(storage)).toEqual(new Set(['views', 'tools']));
  });

  it('tolerates a missing storage', () => {
    expect(readCollapsedGroups(null)).toEqual(new Set());
    expect(() => writeCollapsedGroups(null, new Set(['views']))).not.toThrow();
  });

  it('toggles without mutating its input', () => {
    const before = new Set(['views']);
    const folded = toggleCollapsedGroup(before, 'tools');
    expect(folded).toEqual(new Set(['views', 'tools']));
    expect(before).toEqual(new Set(['views']));
    expect(toggleCollapsedGroup(folded, 'views')).toEqual(new Set(['tools']));
  });
});
