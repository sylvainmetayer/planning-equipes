import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { responsableGuard } from './responsable.guard';
import { SessionStore } from './session.store';

function guard(roles: string[] | null) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: SessionStore,
        useValue: {
          ready: () => Promise.resolve(),
          statut: () =>
            roles === null ? null : { authentifie: true, nom: 'a@b.fr', roles, compteId: 'c' },
        },
      },
    ],
  });
  const state = TestBed.inject(Router).routerState.snapshot;
  return TestBed.runInInjectionContext(() => responsableGuard(new ActivatedRouteSnapshot(), state));
}

describe('responsableGuard', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('sends a responsable de stand alone to their screen', async () => {
    const result = await guard(['responsable_stand']);
    expect(result).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/responsable');
  });

  it('lets an organiser, an administrator and an unread session through', async () => {
    expect(await guard(['organisateur', 'responsable_stand'])).toBe(true);
    expect(await guard(['admin'])).toBe(true);
    expect(await guard(null)).toBe(true);
  });
});
