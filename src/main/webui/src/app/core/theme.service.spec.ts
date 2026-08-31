// The signal side of the theme preference (issue #317): what the toolbar reads,
// and the one thing the pure functions cannot cover — the machine changing its
// mind while the page is open.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ThemeService } from './theme.service';

const STORAGE_KEY = 'planning-equipes.theme';

/** A `matchMedia` whose answer this test drives, standing in for the OS setting. */
class FakeMediaQueryList {
  matches = false;
  private readonly listeners = new Set<(event: MediaQueryListEvent) => void>();

  addEventListener(_type: string, listener: (event: MediaQueryListEvent) => void): void {
    this.listeners.add(listener);
  }

  removeEventListener(_type: string, listener: (event: MediaQueryListEvent) => void): void {
    this.listeners.delete(listener);
  }

  emit(matches: boolean): void {
    this.matches = matches;
    for (const listener of this.listeners) {
      listener({ matches } as MediaQueryListEvent);
    }
  }

  get listenerCount(): number {
    return this.listeners.size;
  }
}

describe('ThemeService', () => {
  let media: FakeMediaQueryList;
  const realMatchMedia = window.matchMedia;

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    document.documentElement.style.colorScheme = '';
    media = new FakeMediaQueryList();
    // jsdom ships no matchMedia at all, which is also the browser case this
    // service has to survive: the fake is installed per test, not globally.
    Object.defineProperty(window, 'matchMedia', { value: () => media, configurable: true, writable: true });
  });

  afterEach(() => {
    Object.defineProperty(window, 'matchMedia', { value: realMatchMedia, configurable: true, writable: true });
    localStorage.removeItem(STORAGE_KEY);
    document.documentElement.style.colorScheme = '';
  });

  function service(): ThemeService {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    return TestBed.inject(ThemeService);
  }

  it('starts on "system" and resolves to what the machine asks for', () => {
    media.matches = true;
    const theme = service();
    expect(theme.preference()).toBe('system');
    expect(theme.scheme()).toBe('dark');
  });

  it('follows the machine changing its mind while the page is open', () => {
    const theme = service();
    expect(theme.scheme()).toBe('light');
    media.emit(true);
    expect(theme.scheme()).toBe('dark');
  });

  it('keeps an explicit choice when the machine changes its mind', () => {
    const theme = service();
    theme.set('light');
    media.emit(true);
    expect(theme.scheme()).toBe('light');
    expect(theme.preference()).toBe('light');
  });

  it('persists the choice and writes it on the root element', () => {
    const theme = service();
    theme.set('dark');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
  });

  it('restores the stored choice on the next visit', () => {
    localStorage.setItem(STORAGE_KEY, 'dark');
    media.matches = false;
    expect(service().scheme()).toBe('dark');
  });

  it('cycles système → clair → sombre → système', () => {
    const theme = service();
    expect(theme.cycle()).toBe('light');
    expect(theme.cycle()).toBe('dark');
    expect(theme.cycle()).toBe('system');
    expect(document.documentElement.style.colorScheme).toBe('light dark');
  });

  it('drops its media listener with the injector', () => {
    service();
    expect(media.listenerCount).toBe(1);
    TestBed.resetTestingModule();
    expect(media.listenerCount).toBe(0);
  });

  it('works at all where there is no matchMedia', () => {
    Object.defineProperty(window, 'matchMedia', { value: undefined, configurable: true, writable: true });
    const theme = service();
    expect(theme.scheme()).toBe('light');
    theme.set('dark');
    expect(theme.scheme()).toBe('dark');
  });
});
