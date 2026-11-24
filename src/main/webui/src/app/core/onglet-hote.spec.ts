// The tab of a hosting page: read from `?onglet=`, followed when the router
// reuses the page, and changed by rewriting the address with the tab alone.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, runInInjectionContext, Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ongletHote } from './onglet-hote';

type Onglet = 'a' | 'b';
const read = (value: string | null): Onglet => (value === 'b' ? 'b' : 'a');

describe('ongletHote', () => {
  const replaceState = vi.fn();
  let path: string;
  let route: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  function host(canLeave?: () => Promise<boolean>) {
    // The router's first emission is the address the page was opened on.
    route.next(convertToParamMap(Object.fromEntries(new URLSearchParams(path.split('?')[1]))));
    return runInInjectionContext(TestBed.inject(Injector), () => ongletHote(read, 'a', canLeave));
  }

  beforeEach(() => {
    replaceState.mockReset();
    path = '/page';
    route = new BehaviorSubject(convertToParamMap({}));
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Location, useValue: { path: () => path, replaceState } },
        { provide: ActivatedRoute, useValue: { queryParamMap: route } },
      ],
    });
  });

  it('opens on the tab the address names, the default for an unknown one', () => {
    path = '/page?onglet=b';
    expect(host().onglet()).toBe('b');
    path = '/page?onglet=zzz';
    expect(host().onglet()).toBe('a');
  });

  it('follows the router when it reuses the page with another tab', () => {
    const hote = host();
    route.next(convertToParamMap({ onglet: 'b' }));
    expect(hote.onglet()).toBe('b');
  });

  it('keeps the page-level ?avance=1 fold open when the tab changes', async () => {
    path = '/page?onglet=a&avance=1&q=x';
    const hote = host();

    await hote.changer('b');
    expect(replaceState).toHaveBeenLastCalledWith('/page?onglet=b&avance=1');

    await hote.changer('a');
    expect(replaceState).toHaveBeenLastCalledWith('/page?avance=1');
  });

  it('writes the tab alone, and the bare path for the default tab', async () => {
    path = '/page?onglet=a&q=x';
    const hote = host();

    await hote.changer('b');
    expect(replaceState).toHaveBeenLastCalledWith('/page?onglet=b');
    expect(hote.onglet()).toBe('b');

    await hote.changer('a');
    expect(replaceState).toHaveBeenLastCalledWith('/page');
    expect(hote.onglet()).toBe('a');
  });

  it('keeps the root path when the page sits on it', async () => {
    path = '?onglet=b';
    const hote = host();
    await hote.changer('a');
    expect(replaceState).toHaveBeenLastCalledWith('/');
  });

  it('does nothing for the tab already on screen', async () => {
    await host().changer('a');
    expect(replaceState).not.toHaveBeenCalled();
  });

  it('stays on the tab when it refuses to be left', async () => {
    const hote = host(() => Promise.resolve(false));
    await hote.changer('b');
    expect(replaceState).not.toHaveBeenCalled();
    expect(hote.onglet()).toBe('a');
  });
});
