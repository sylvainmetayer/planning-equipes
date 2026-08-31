import { describe, expect, it } from 'vitest';
import {
  applyThemePreference,
  colorSchemeFor,
  nextThemePreference,
  readThemePreference,
  resolveScheme,
  writeThemePreference
} from './theme-preference';

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

describe('theme-preference', () => {
  it('follows the system when nothing was ever chosen', () => {
    expect(readThemePreference(fakeStorage())).toBe('system');
  });

  it('falls back to the system on a value it does not know', () => {
    expect(readThemePreference(fakeStorage('sepia'))).toBe('system');
    expect(readThemePreference(fakeStorage(''))).toBe('system');
  });

  it('round-trips the three preferences through storage', () => {
    for (const preference of ['system', 'light', 'dark'] as const) {
      const storage = fakeStorage();
      writeThemePreference(storage, preference);
      expect(readThemePreference(storage)).toBe(preference);
    }
  });

  it('tolerates a storage that is absent or refuses to answer', () => {
    expect(readThemePreference(null)).toBe('system');
    expect(() => writeThemePreference(null, 'dark')).not.toThrow();
    const hostile = {
      getItem(): string {
        throw new Error('blocked');
      },
      setItem(): void {
        throw new Error('blocked');
      }
    };
    expect(readThemePreference(hostile)).toBe('system');
    expect(() => writeThemePreference(hostile, 'dark')).not.toThrow();
  });

  it('cycles système → clair → sombre → système', () => {
    expect(nextThemePreference('system')).toBe('light');
    expect(nextThemePreference('light')).toBe('dark');
    expect(nextThemePreference('dark')).toBe('system');
  });

  it('maps the preference to a CSS color-scheme, system meaning "let the browser pick"', () => {
    expect(colorSchemeFor('system')).toBe('light dark');
    expect(colorSchemeFor('light')).toBe('light');
    expect(colorSchemeFor('dark')).toBe('dark');
  });

  it('resolves what is painted: the machine decides only under "system"', () => {
    expect(resolveScheme('system', true)).toBe('dark');
    expect(resolveScheme('system', false)).toBe('light');
    // The point of an explicit choice: it outranks the machine, both ways.
    expect(resolveScheme('light', true)).toBe('light');
    expect(resolveScheme('dark', false)).toBe('dark');
  });

  it('writes the scheme on the element the whole document inherits from', () => {
    const root = document.createElement('html');
    applyThemePreference('dark', root);
    expect(root.style.colorScheme).toBe('dark');
    applyThemePreference('system', root);
    expect(root.style.colorScheme).toBe('light dark');
  });
});
