import { describe, expect, it } from 'vitest';
import { readSingleKeyShortcuts, writeSingleKeyShortcuts } from './single-key-shortcuts';

function memoryStorage(): Pick<Storage, 'getItem' | 'setItem'> {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => void values.set(key, value),
  };
}

describe('single-key shortcuts preference', () => {
  it('is on by default, as the shortcuts have always been', () => {
    expect(readSingleKeyShortcuts(memoryStorage())).toBe(true);
    expect(readSingleKeyShortcuts(null)).toBe(true);
  });

  it('survives a reload once turned off', () => {
    const storage = memoryStorage();
    writeSingleKeyShortcuts(storage, false);

    expect(readSingleKeyShortcuts(storage)).toBe(false);
  });

  it('reads an unreadable storage as on rather than failing', () => {
    const broken = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
    };

    expect(() => writeSingleKeyShortcuts(broken, false)).not.toThrow();
    expect(readSingleKeyShortcuts(broken)).toBe(true);
  });
});
