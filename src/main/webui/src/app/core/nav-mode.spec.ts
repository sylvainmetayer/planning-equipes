import { describe, expect, it } from 'vitest';
import { nextNavMode, readNavMode, writeNavMode } from './nav-mode';

function fakeStorage(
  initial?: string,
): Pick<Storage, 'getItem' | 'setItem'> & { value: string | null } {
  return {
    value: initial ?? null,
    getItem(): string | null {
      return this.value;
    },
    setItem(_key: string, value: string): void {
      this.value = value;
    },
  };
}

describe('nav-mode', () => {
  it('reads « simple » when nothing was stored', () => {
    expect(readNavMode(fakeStorage())).toBe('simple');
  });

  it('reads « simple » on any value it does not know', () => {
    expect(readNavMode(fakeStorage('expert'))).toBe('simple');
    expect(readNavMode(fakeStorage(''))).toBe('simple');
  });

  it('round-trips through storage', () => {
    const storage = fakeStorage();
    writeNavMode(storage, 'avance');
    expect(readNavMode(storage)).toBe('avance');
    writeNavMode(storage, 'simple');
    expect(readNavMode(storage)).toBe('simple');
  });

  it('tolerates a missing or refusing storage', () => {
    expect(readNavMode(null)).toBe('simple');
    expect(() => writeNavMode(null, 'avance')).not.toThrow();
    const refusing = {
      getItem(): string | null {
        throw new Error('quota');
      },
      setItem(): void {
        throw new Error('quota');
      },
    };
    expect(readNavMode(refusing)).toBe('simple');
    expect(() => writeNavMode(refusing, 'avance')).not.toThrow();
  });

  it('toggles between the two modes', () => {
    expect(nextNavMode('simple')).toBe('avance');
    expect(nextNavMode('avance')).toBe('simple');
  });
});
