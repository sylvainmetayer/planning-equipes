// The consignes store: one read shared by every badge, the lookups the badges
// do, and a failure that keeps the last good state on screen.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConsignesApi } from './api/consignes-api';
import { ConsignesStore } from './consignes.store';
import { EtatConsignes } from './models';

const ETAT: EtatConsignes = {
  aujourdhui: '2026-07-10',
  prereglages: [],
  indicateurs: [],
  consignes: [
    {
      date: '2026-07-11',
      fermetureDebut: '12:00:00',
      fermetureFin: '18:00:00',
      motif: 'Canicule',
      prereglage: null,
      fenetres: [],
      ouvertures: [],
      creneauxAjoutes: [41, 42],
      creeLe: null,
      modifieLe: null,
    },
    {
      date: '2026-07-12',
      fermetureDebut: '12:00:00',
      fermetureFin: null,
      motif: 'Orage',
      prereglage: null,
      fenetres: [],
      ouvertures: [],
      creneauxAjoutes: [43],
      creeLe: null,
      modifieLe: null,
    },
  ],
};

describe('ConsignesStore', () => {
  const api = { etat: vi.fn() };

  beforeEach(() => {
    api.etat.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ConsignesApi, useValue: api }],
    });
  });

  it('answers nothing before the first read, then indexes the consignes by date and the créneaux they added', async () => {
    api.etat.mockResolvedValue(ETAT);
    const store = TestBed.inject(ConsignesStore);

    expect(store.etat()).toBeNull();
    expect(store.aujourdhui()).toBeNull();
    expect(store.consigneOf('2026-07-11')).toBeNull();

    await store.reload();

    expect(store.aujourdhui()).toBe('2026-07-10');
    expect(store.consigneOf('2026-07-11')?.motif).toBe('Canicule');
    expect(store.consigneOf('2026-07-13')).toBeNull();
    expect(store.consigneOf(null)).toBeNull();
    expect([...store.creneauxAjoutes()].sort()).toEqual([41, 42, 43]);
    expect(store.error()).toBe('');
  });

  it('keeps the last good state and words the failure when a reload fails', async () => {
    api.etat.mockResolvedValueOnce(ETAT).mockRejectedValueOnce(new Error('serveur injoignable'));
    const store = TestBed.inject(ConsignesStore);

    await store.reload();
    await store.reload();

    expect(store.consignes()).toHaveLength(2);
    expect(store.error()).toBe('serveur injoignable');
    expect(store.loading()).toBe(false);
  });
});
