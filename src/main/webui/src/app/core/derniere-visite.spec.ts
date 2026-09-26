import { describe, expect, it } from 'vitest';
import { countSince, readLastVisit, writeLastVisit } from './derniere-visite';

function memory(): Pick<Storage, 'getItem' | 'setItem'> {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
}

describe('derniere-visite', () => {
  it('reads back the visit it wrote, per screen', () => {
    const storage = memory();
    writeLastVisit(storage, 'echanges', '2026-09-05T08:00:00.000Z');

    expect(readLastVisit(storage, 'echanges')).toBe('2026-09-05T08:00:00.000Z');
    expect(readLastVisit(storage, 'autre')).toBeNull();
  });

  it('survives a storage that refuses everything', () => {
    const hostile = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };

    expect(readLastVisit(hostile, 'echanges')).toBeNull();
    expect(() => writeLastVisit(hostile, 'echanges', 'x')).not.toThrow();
    expect(readLastVisit(null, 'echanges')).toBeNull();
  });

  it('counts what came after the previous visit, and nothing on a first one', () => {
    const instants = ['2026-09-05T07:00:00Z', '2026-09-05T09:00:00Z', null];

    expect(countSince(instants, '2026-09-05T08:00:00Z')).toBe(1);
    expect(countSince(instants, null)).toBe(0);
  });
});
