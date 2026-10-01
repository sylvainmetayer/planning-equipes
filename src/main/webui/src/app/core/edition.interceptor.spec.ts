import { HttpErrorResponse, HttpRequest, HttpResponse } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { editionInterceptor } from './edition.interceptor';
import { clearStoredEditionId, getStoredEditionId } from './edition-courante';

/** Runs the interceptor and hands back the request that reached the next handler. */
function intercept(url: string): HttpRequest<unknown> {
  const next = vi.fn().mockReturnValue(of(new HttpResponse()));
  editionInterceptor(new HttpRequest('GET', url), next);
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
      firstValueFrom(editionInterceptor(new HttpRequest('GET', '/api/stands'), next)),
    ).rejects.toBe(refusal);
    expect(getStoredEditionId()).toBeNull();
  });

  it('keeps the stored edition on any other refusal', async () => {
    localStorage.setItem('planning-equipes.editionId', 'E1');
    const refusal = new HttpErrorResponse({ status: 400, error: { message: 'invalide' } });
    const next = vi.fn().mockReturnValue(throwError(() => refusal));

    await expect(
      firstValueFrom(editionInterceptor(new HttpRequest('GET', '/api/stands'), next)),
    ).rejects.toBe(refusal);
    expect(getStoredEditionId()).toBe('E1');
  });
});
