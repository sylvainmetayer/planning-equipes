// The freeze store: one read shared by every padlock, what a freeze and a lift
// leave behind, and a failure that says so without pretending nothing is frozen.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from './api/editions-api';
import { GelReferentielStore } from './gel-referentiel.store';
import { EtatGel } from './models';

const OUVERTES: EtatGel[] = [
  { famille: 'STANDS', libelle: 'Stands', fige: false, figeLe: null },
  { famille: 'CRENEAUX', libelle: 'Créneaux', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'TYPOLOGIES_EMPLACEMENTS', libelle: 'Typologies', fige: false, figeLe: null },
  { famille: 'COMPETENCES', libelle: 'Compétences', fige: false, figeLe: null },
];

describe('GelReferentielStore', () => {
  let api: {
    gel: ReturnType<typeof vi.fn>;
    freeze: ReturnType<typeof vi.fn>;
    lift: ReturnType<typeof vi.fn>;
  };
  let store: GelReferentielStore;

  beforeEach(() => {
    api = {
      gel: vi.fn().mockResolvedValue(OUVERTES),
      freeze: vi.fn(),
      lift: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: EditionsApi, useValue: api }],
    });
    store = TestBed.inject(GelReferentielStore);
  });

  it('reads the freeze once however many screens ask', async () => {
    await Promise.all([store.ensureLoaded(), store.ensureLoaded()]);
    await store.ensureLoaded();

    expect(api.gel).toHaveBeenCalledOnce();
    expect(store.isFrozen('CRENEAUX')).toBe(true);
    expect(store.isFrozen('STANDS')).toBe(false);
    expect(store.frozen('CRENEAUX')?.figeLe).toBe('2026-07-01T08:00:00Z');
    expect(store.anyFrozen()).toBe(true);
    expect(store.frozenFamilies()).toEqual(['CRENEAUX']);
  });

  it('keeps the families in the server order after a freeze and a lift', async () => {
    await store.ensureLoaded();
    api.freeze.mockResolvedValue({ ...OUVERTES[0], fige: true, figeLe: '2026-07-02T08:00:00Z' });

    await store.freeze('STANDS');
    await store.lift('CRENEAUX');

    expect(store.states().map((state) => state.famille)).toEqual([
      'STANDS',
      'CRENEAUX',
      'TYPOLOGIES_EMPLACEMENTS',
      'COMPETENCES',
    ]);
    expect(store.isFrozen('STANDS')).toBe(true);
    expect(store.isFrozen('CRENEAUX')).toBe(false);
    expect(store.busy()).toBe(false);
  });

  it('says a failed read and treats an unexpected answer as nothing frozen', async () => {
    api.gel.mockRejectedValueOnce(new Error('Serveur injoignable'));
    await store.reload();
    expect(store.error()).toBe('Serveur injoignable');

    api.gel.mockResolvedValueOnce({});
    await store.reload();
    expect(store.error()).toBe('');
    expect(store.anyFrozen()).toBe(false);
  });
});
