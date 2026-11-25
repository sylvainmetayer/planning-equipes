import { HttpErrorResponse, HttpRequest, HttpResponse } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { editionInterceptor } from './edition.interceptor';
import { clearStoredEditionId, getStoredEditionId } from './edition-courante';
import { NotificationService } from './notification.service';

/** Runs the interceptor and hands back the request that reached the next handler. */
function intercept(url: string): HttpRequest<unknown> {
  const next = vi.fn().mockReturnValue(of(new HttpResponse()));
  TestBed.runInInjectionContext(() => editionInterceptor(new HttpRequest('GET', url), next));
  return next.mock.calls[0][0] as HttpRequest<unknown>;
}

describe('editionInterceptor', () => {
  afterEach(() => {
    clearStoredEditionId();
    sessionStorage.clear();
  });

  it('sends no header until an edition has been picked', () => {
    expect(intercept('/api/stands').headers.has('X-Edition-Id')).toBe(false);
  });

  it('tags backend calls with the stored edition', () => {
    localStorage.setItem('planning-equipes.editionId', 'ANNEE-2026');
    expect(intercept('/api/stands').headers.get('X-Edition-Id')).toBe('ANNEE-2026');
  });

  it('leaves non-API requests alone', () => {
    localStorage.setItem('planning-equipes.editionId', 'ANNEE-2026');
    expect(intercept('/i18n/messages.en.json').headers.has('X-Edition-Id')).toBe(false);
  });

  it('forgets a stored edition the server no longer knows, so the guard picks again', async () => {
    localStorage.setItem('planning-equipes.editionId', 'SUPPRIMEE');
    // A reload has just happened: the interceptor must not loop on another one.
    sessionStorage.setItem('planning-equipes.editionReload', String(Date.now()));
    const refusal = new HttpErrorResponse({
      status: 400,
      error: { message: 'inconnue', code: 'EDITION_INCONNUE' },
    });
    const next = vi.fn().mockReturnValue(throwError(() => refusal));

    await expect(
      firstValueFrom(
        TestBed.runInInjectionContext(() =>
          editionInterceptor(new HttpRequest('GET', '/api/stands'), next),
        ),
      ),
    ).rejects.toBe(refusal);
    expect(getStoredEditionId()).toBeNull();
  });

  it('keeps the stored edition on any other refusal', async () => {
    localStorage.setItem('planning-equipes.editionId', 'E1');
    const refusal = new HttpErrorResponse({ status: 400, error: { message: 'invalide' } });
    const next = vi.fn().mockReturnValue(throwError(() => refusal));

    await expect(
      firstValueFrom(
        TestBed.runInInjectionContext(() =>
          editionInterceptor(new HttpRequest('GET', '/api/stands'), next),
        ),
      ),
    ).rejects.toBe(refusal);
    expect(getStoredEditionId()).toBe('E1');
  });

  it('tells the user about an outward action refused by an inactive edition', async () => {
    const notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: NotificationService, useValue: { notify } }],
    });
    const refusal = new HttpErrorResponse({
      status: 409,
      error: { message: 'inactive', code: 'EDITION_INACTIVE' },
    });
    const next = vi.fn().mockReturnValue(throwError(() => refusal));

    await expect(
      firstValueFrom(
        TestBed.runInInjectionContext(() =>
          editionInterceptor(new HttpRequest('POST', '/api/planning/publication', null), next),
        ),
      ),
    ).rejects.toBe(refusal);
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify.mock.calls[0][0]).toMatchObject({
      variant: 'error',
      lien: { route: '/editions' },
    });
  });

  it('stays silent on any other 409', async () => {
    const notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: NotificationService, useValue: { notify } }],
    });
    const refusal = new HttpErrorResponse({ status: 409, error: { code: 'REFERENTIEL_FIGE' } });
    const next = vi.fn().mockReturnValue(throwError(() => refusal));

    await expect(
      firstValueFrom(
        TestBed.runInInjectionContext(() =>
          editionInterceptor(new HttpRequest('POST', '/api/stands', null), next),
        ),
      ),
    ).rejects.toBe(refusal);
    expect(notify).not.toHaveBeenCalled();
  });
});
