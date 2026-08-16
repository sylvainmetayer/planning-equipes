import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { GroupeStore } from './groupe.store';
import { Groupe } from './models';
import { clearStoredGroupeId, getStoredGroupeId } from './groupe-courant';

function groupe(id: string, nom: string, defaut = false): Groupe {
  return { id, nom, defaut, creeLe: null };
}

describe('GroupeStore', () => {
  const get = vi.fn();
  let store: GroupeStore;

  beforeEach(() => {
    get.mockReset();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: { get } }]
    });
    store = TestBed.inject(GroupeStore);
  });

  afterEach(() => clearStoredGroupeId());

  function respond(groupes: Groupe[], courant: Groupe): void {
    get.mockImplementation((url: string) => Promise.resolve(url.endsWith('/courant') ? courant : groupes));
  }

  it('lists every group and the one this tab resolved to', async () => {
    respond([groupe('A', 'Année 2025', true), groupe('B', 'Année 2026')], groupe('B', 'Année 2026'));
    await store.reload();
    expect(store.groupes()).toHaveLength(2);
    expect(store.courant()?.id).toBe('B');
    expect(store.autres().map((g) => g.id)).toEqual(['A']);
  });

  it('forgets a stored id the server did not honour', async () => {
    localStorage.setItem('planning-equipes.groupeId', 'SUPPRIME');
    respond([groupe('A', 'Année 2025', true)], groupe('A', 'Année 2025', true));

    await store.reload();

    expect(getStoredGroupeId()).toBeNull();
    expect(store.courant()?.id).toBe('A');
  });

  it('keeps a stored id the server honoured', async () => {
    localStorage.setItem('planning-equipes.groupeId', 'B');
    respond([groupe('A', 'Année 2025', true), groupe('B', 'Année 2026')], groupe('B', 'Année 2026'));

    await store.reload();

    expect(getStoredGroupeId()).toBe('B');
  });
});
