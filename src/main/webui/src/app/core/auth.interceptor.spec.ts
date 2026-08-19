import { HttpErrorResponse, HttpRequest, type HttpEvent } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router } from '@angular/router';
import { throwError, of, firstValueFrom, type Observable } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authInterceptor } from './auth.interceptor';

class FakeRouter {
  navigateByUrl = vi.fn(async () => true);
}

describe('authInterceptor', () => {
  let router: FakeRouter;

  beforeEach(() => {
    router = new FakeRouter();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: Router, useValue: router }]
    });
  });

  /** Runs the interceptor on `url` with a next handler answering `status`. */
  function interceptEnErreur(url: string, status: number): Observable<HttpEvent<unknown>> {
    const next = vi
      .fn()
      .mockReturnValue(throwError(() => new HttpErrorResponse({ status, url })));
    return TestBed.runInInjectionContext(() =>
      authInterceptor(new HttpRequest('GET', url), next)
    );
  }

  it('renvoie vers /login sur un 401 des API admin, en repropageant l’erreur', async () => {
    await expect(firstValueFrom(interceptEnErreur('/api/stands', 401))).rejects.toMatchObject({
      status: 401
    });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('laisse tranquilles les routes publiques par conception', async () => {
    await expect(
      firstValueFrom(interceptEnErreur('/api/espace-animateur/jeton-1', 401))
    ).rejects.toMatchObject({ status: 401 });
    await expect(firstValueFrom(interceptEnErreur('/api/auth/me', 401))).rejects.toMatchObject({
      status: 401
    });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('ne redirige ni sur un autre statut, ni hors /api', async () => {
    await expect(firstValueFrom(interceptEnErreur('/api/stands', 500))).rejects.toMatchObject({
      status: 500
    });
    await expect(
      firstValueFrom(interceptEnErreur('/i18n/messages.en.json', 401))
    ).rejects.toMatchObject({ status: 401 });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('un appel réussi traverse sans détour', async () => {
    const next = vi.fn().mockReturnValue(of({ type: 0 } as HttpEvent<unknown>));
    const event = await firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(new HttpRequest('GET', '/api/stands'), next))
    );
    expect(event).toEqual({ type: 0 });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
