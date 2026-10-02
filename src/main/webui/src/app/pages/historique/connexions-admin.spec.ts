// « Connexions » of the Historique page: one page of the admin logins, the
// next one asked by the cursor of the last line shown, each line worded by
// what came of the attempt. Created, never rendered, as the page specs are.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { AdminLoginView } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ConnexionsAdmin } from './connexions-admin';
import { PAGE_HISTORIQUE, loginLabel } from './historique';

function adminLogin(partial: Partial<AdminLoginView> = {}): AdminLoginView {
  return {
    id: 1,
    survenuLe: '2026-09-07T14:32:00Z',
    evenement: 'CONNEXION',
    adresse: '203.0.113.7',
    ...partial,
  };
}

describe('ConnexionsAdmin', () => {
  const api = fakeOf<AnalysesApi>({ loginJournal: () => Promise.resolve([adminLogin()]) });

  beforeEach(() => {
    api.loginJournal.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideFake(AnalysesApi, api)],
    });
  });

  function create(): ConnexionsAdmin {
    const fixture = TestBed.createComponent(ConnexionsAdmin);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('reads the first page, one line more than it shows', async () => {
    api.loginJournal.mockResolvedValue([adminLogin()]);
    const component = create();
    await vi.waitFor(() => expect(component['chargement']()).toBe(false));

    expect(api.loginJournal).toHaveBeenCalledExactlyOnceWith(null, PAGE_HISTORIQUE + 1);
    expect(component['lignes']()).toHaveLength(1);
    expect(component['suivant']()).toBeNull();
  });

  it('asks the next page after the last line shown', async () => {
    const full = Array.from({ length: PAGE_HISTORIQUE + 1 }, (_, i) => adminLogin({ id: 900 - i }));
    api.loginJournal.mockResolvedValueOnce(full);
    api.loginJournal.mockResolvedValueOnce([adminLogin({ id: 5, evenement: 'ECHEC' })]);
    const component = create();
    await vi.waitFor(() => expect(component['chargement']()).toBe(false));
    const lastShown = 900 - (PAGE_HISTORIQUE - 1);
    expect(component['suivant']()).toBe(lastShown);

    await component['loadMore']();

    expect(api.loginJournal).toHaveBeenLastCalledWith(lastShown, PAGE_HISTORIQUE + 1);
    expect(component['lignes']()).toHaveLength(PAGE_HISTORIQUE + 1);
    expect(component['suivant']()).toBeNull();
  });
});

describe('loginLabel', () => {
  it('words each outcome', () => {
    expect(loginLabel(adminLogin())).toBe('Connexion réussie');
    expect(loginLabel(adminLogin({ evenement: 'ECHEC' }))).toBe('Échec');
    expect(loginLabel(adminLogin({ evenement: 'VERROUILLAGE' }))).toBe('Adresse verrouillée');
  });
});
