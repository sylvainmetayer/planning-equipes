// The freeze store: one read shared by every padlock, re-read on demand after a
// phase gesture, and a failure that says so without pretending nothing is frozen.

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
  let api: { gel: ReturnType<typeof vi.fn> };
  let store: GelReferentielStore;

  beforeEach(() => {
    api = { gel: vi.fn().mockResolvedValue(OUVERTES) };
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

  it('reads every family again on demand, since a phase moves them all', async () => {
    await store.ensureLoaded();
    api.gel.mockResolvedValueOnce(
      OUVERTES.map((state) => ({ ...state, fige: true, figeLe: '2026-07-02T08:00:00Z' })),
    );

    await store.reload();
    expect(store.frozenFamilies()).toEqual([
      'STANDS',
      'CRENEAUX',
      'TYPOLOGIES_EMPLACEMENTS',
      'COMPETENCES',
    ]);

    api.gel.mockResolvedValueOnce(
      OUVERTES.map((state) => ({ ...state, fige: false, figeLe: null })),
    );
    await store.reload();
    expect(store.anyFrozen()).toBe(false);
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
