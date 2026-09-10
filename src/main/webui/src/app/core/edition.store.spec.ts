import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { Edition } from './models';
import { clearStoredEditionId, getStoredEditionId } from './edition-courante';

function edition(id: string, nom: string, defaut = false): Edition {
  return { id, nom, defaut, creeLe: null };
}

describe('EditionStore', () => {
  const get = vi.fn();
  let store: EditionStore;

  beforeEach(() => {
    get.mockReset();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: { get } }],
    });
    store = TestBed.inject(EditionStore);
  });

  afterEach(() => clearStoredEditionId());

  function respond(editions: Edition[], courant: Edition): void {
    get.mockImplementation((url: string) =>
      Promise.resolve(url.endsWith('/courant') ? courant : editions),
    );
  }

  it('lists every edition and the one this tab resolved to', async () => {
    respond(
      [edition('A', 'Année 2025', true), edition('B', 'Année 2026')],
      edition('B', 'Année 2026'),
    );
    await store.reload();
    expect(store.editions()).toHaveLength(2);
    expect(store.courant()?.id).toBe('B');
    expect(store.autres().map((e) => e.id)).toEqual(['A']);
  });

  it('forgets a stored id the server did not honour', async () => {
    localStorage.setItem('planning-equipes.editionId', 'SUPPRIME');
    respond([edition('A', 'Année 2025', true)], edition('A', 'Année 2025', true));

    await store.reload();

    expect(getStoredEditionId()).toBeNull();
    expect(store.courant()?.id).toBe('A');
  });

  it('keeps a stored id the server honoured', async () => {
    localStorage.setItem('planning-equipes.editionId', 'B');
    respond(
      [edition('A', 'Année 2025', true), edition('B', 'Année 2026')],
      edition('B', 'Année 2026'),
    );

    await store.reload();

    expect(getStoredEditionId()).toBe('B');
  });
});
