// The two helpers exist for one property the resource lacks: a failed reload
// must not blank the screen. Exercised on a real `resource()`, not a double,
// so a change in Angular's own semantics (value throwing in error state, the
// previous value kept while reloading) is caught here and nowhere else.

import {
  Injector,
  provideZonelessChangeDetection,
  resource,
  runInInjectionContext,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { errorText, retainedValue } from './resource-state';

describe('resource-state', () => {
  const loader = vi.fn<() => Promise<{ total: number }>>();

  beforeEach(() => {
    loader.mockReset();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  function build() {
    return runInInjectionContext(TestBed.inject(Injector), () => {
      const source = resource({ loader: () => loader() });
      return { source, value: retainedValue(source), error: errorText(source) };
    });
  }

  it('answers null before the first value, then the value', async () => {
    loader.mockResolvedValue({ total: 3 });
    const { value, error } = build();

    expect(value()).toBeNull();
    expect(error()).toBe('');
    await vi.waitFor(() => expect(value()).toEqual({ total: 3 }));
  });

  it('keeps the last good value when a reload fails, next to the failure', async () => {
    loader.mockResolvedValueOnce({ total: 3 });
    const { source, value, error } = build();
    await vi.waitFor(() => expect(value()).toEqual({ total: 3 }));

    loader.mockRejectedValueOnce(new Error('Serveur injoignable.'));
    source.reload();
    await vi.waitFor(() => expect(error()).toContain('Serveur injoignable.'));

    // The resource itself has nothing to show any more; the screen still does.
    expect(() => source.value()).toThrow();
    expect(value()).toEqual({ total: 3 });
  });

  it('clears the failure on the next success, and shows the new value', async () => {
    loader.mockRejectedValueOnce(new Error('Serveur injoignable.'));
    const { source, value, error } = build();
    await vi.waitFor(() => expect(error()).not.toBe(''));
    expect(value()).toBeNull();

    loader.mockResolvedValueOnce({ total: 5 });
    source.reload();
    await vi.waitFor(() => expect(error()).toBe(''));

    expect(value()).toEqual({ total: 5 });
  });

  it('words the failure with the formatter it is given', async () => {
    loader.mockRejectedValueOnce(new Error('Serveur injoignable.'));
    const { source } = build();
    const shouted = errorText(source, (error) => String((error as Error).message).toUpperCase());

    await vi.waitFor(() => expect(shouted()).toBe('SERVEUR INJOIGNABLE.'));
  });
});
