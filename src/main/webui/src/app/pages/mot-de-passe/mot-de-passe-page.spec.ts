import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { ChoosePasswordPage } from './mot-de-passe-page';

describe('ChoosePasswordPage', () => {
  const api = { choosePassword: vi.fn() };

  function monter(fragment: string | null) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    });
    const fixture = TestBed.createComponent(ChoosePasswordPage);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => api.choosePassword.mockReset());

  it('sends the token of the fragment with the password, once the two agree and are long enough', async () => {
    api.choosePassword.mockResolvedValue(undefined);
    const page = monter('JETON').componentInstance;
    page['password'].set('court');
    page['confirmation'].set('court');
    expect(page['valid']()).toBe(false);
    page['password'].set('une phrase assez longue');
    expect(page['valid']()).toBe(false);
    page['confirmation'].set('une phrase assez longue');
    expect(page['valid']()).toBe(true);
    await page['choisir'](new Event('submit'));
    expect(api.choosePassword).toHaveBeenCalledWith('JETON', 'une phrase assez longue');
    expect(page['choisi']()).toBe(true);
  });

  it('shows the refusal of an expired or used link', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: 'JETON' } } },
      ],
    });
    const fixture = TestBed.createComponent(ChoosePasswordPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page['password'].set('une phrase assez longue');
    page['confirmation'].set('une phrase assez longue');
    const http = TestBed.inject(HttpTestingController);
    const fin = page['choisir'](new Event('submit'));
    const request = http.expectOne('/api/auth/mot-de-passe');
    expect(request.request.body).toEqual({ token: 'JETON', password: 'une phrase assez longue' });
    request.flush(
      { message: "Ce lien n'est pas valable" },
      { status: 400, statusText: 'Bad Request' },
    );
    await fin;
    expect(page['erreur']()).toContain('lien');
    expect(page['choisi']()).toBe(false);
  });

  it('takes the token out of the address bar as soon as it is read', () => {
    const replace = vi.spyOn(window.history, 'replaceState');
    window.history.replaceState(null, '', '/mot-de-passe#SECRET');
    replace.mockClear();
    monter('SECRET');
    expect(replace).toHaveBeenCalled();
    expect(window.location.hash).toBe('');
    replace.mockRestore();
  });

  it('says why the button waits, counting code points like the server', () => {
    const fixture = monter('JETON');
    const page = fixture.componentInstance;
    page['password'].set('court');
    expect(page['raison']()).toContain('7');
    page['password'].set('une phrase assez longue');
    page['confirmation'].set('une phrase assez longu');
    expect(page['raison']()).toContain('identiques');
    // twelve code points, however they are encoded
    page['password'].set('e\u0301'.repeat(12));
    page['confirmation'].set('\u00e9'.repeat(12));
    expect(page['raison']()).toBe('');
  });

  it('does nothing without a token', async () => {
    const fixture = monter(null);
    const page = fixture.componentInstance;
    page['password'].set('une phrase assez longue');
    page['confirmation'].set('une phrase assez longue');
    await page['choisir'](new Event('submit'));
    expect(api.choosePassword).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('lien reçu');
  });
});
