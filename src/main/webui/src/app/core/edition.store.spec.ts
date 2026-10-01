import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { Edition, EditionSituation } from './models';
import { clearStoredEditionId, getStoredEditionId } from './edition-courante';

function edition(id: string, nom: string, active = false): Edition {
  return { id, nom, active, creeLe: null };
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

  function respond(
    editions: Edition[],
    courant: Edition,
    situations: EditionSituation[] = [],
  ): void {
    get.mockImplementation((url: string) => {
      if (url.endsWith('/courant')) {
        return Promise.resolve(courant);
      }
      return Promise.resolve(url.endsWith('/situations') ? situations : editions);
    });
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

  it('names the active edition, the one allowed to reach outside', async () => {
    respond(
      [edition('A', 'Année 2025'), edition('B', 'Année 2026', true)],
      edition('A', 'Année 2025'),
    );
    await store.reload();
    expect(store.active()?.id).toBe('B');
  });

  it('loads what the editions state asks of the organiser, and survives its failure', async () => {
    const situation: EditionSituation = {
      type: 'INACTIVE_IMMINENTE',
      edition: edition('B', 'Année 2026'),
      premierJour: '2026-07-10',
      dernierJour: '2026-07-12',
    };
    respond([edition('A', 'Année 2025', true)], edition('A', 'Année 2025', true), [situation]);
    await store.reload();
    expect(store.situations()).toEqual([situation]);

    get.mockImplementation((url: string) => {
      if (url.endsWith('/situations')) {
        return Promise.reject(new Error('boom'));
      }
      return Promise.resolve(url.endsWith('/courant') ? edition('A', 'A') : [edition('A', 'A')]);
    });
    await store.reload();
    expect(store.situations()).toEqual([]);
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
