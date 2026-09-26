// Freezing and lifting: the lift asks first and recalls the phase; a cancelled
// lift writes nothing.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../core/api/editions-api';
import { GelReferentielStore } from '../core/gel-referentiel.store';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';
import { GelReferentielActions, phaseReminder } from './gel-referentiel-actions';

describe('phaseReminder', () => {
  it('recalls a published plan first, then a computed one, and nothing before', () => {
    expect(
      phaseReminder({
        publication: {
          jamaisPublie: false,
          dernierePublicationLe: null,
          personnesAPrevenir: 0,
          envoisEnEchec: 0,
          statut: 'FAIT',
        },
        resolution: {
          resolue: true,
          resoluLe: null,
          score: null,
          scoreHorsPlancher: null,
          faisable: null,
          dataStale: false,
          solveEnCours: false,
          statut: 'FAIT',
          lecture: [],
        },
      }),
    ).toContain('publié');
    expect(
      phaseReminder({
        publication: {
          jamaisPublie: true,
          dernierePublicationLe: null,
          personnesAPrevenir: 0,
          envoisEnEchec: 0,
          statut: 'A_FAIRE',
        },
        resolution: {
          resolue: true,
          resoluLe: null,
          score: null,
          scoreHorsPlancher: null,
          faisable: null,
          dataStale: false,
          solveEnCours: false,
          statut: 'FAIT',
          lecture: [],
        },
      }),
    ).toContain('calculé');
    expect(
      phaseReminder({
        publication: {
          jamaisPublie: true,
          dernierePublicationLe: null,
          personnesAPrevenir: 0,
          envoisEnEchec: 0,
          statut: 'A_FAIRE',
        },
        resolution: {
          resolue: false,
          resoluLe: null,
          score: null,
          scoreHorsPlancher: null,
          faisable: null,
          dataStale: false,
          solveEnCours: false,
          statut: 'A_FAIRE',
          lecture: [],
        },
      }),
    ).toBe('');
  });
});

describe('GelReferentielActions', () => {
  let ask: ReturnType<typeof vi.fn>;
  let store: { freeze: ReturnType<typeof vi.fn>; lift: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let actions: GelReferentielActions;

  beforeEach(() => {
    ask = vi.fn();
    store = {
      freeze: vi.fn().mockResolvedValue(undefined),
      lift: vi.fn().mockResolvedValue(undefined),
    };
    notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ConfirmService, useValue: { ask } },
        { provide: GelReferentielStore, useValue: store },
        { provide: NotificationService, useValue: { notify } },
        {
          provide: EditionsApi,
          useValue: { etat: vi.fn().mockReturnValue(new Promise(() => undefined)) },
        },
      ],
    });
    actions = TestBed.inject(GelReferentielActions);
  });

  it('freezes at once and says so', async () => {
    expect(await actions.freeze('STANDS')).toBe(true);
    expect(store.freeze).toHaveBeenCalledWith('STANDS');
    expect(notify.mock.calls[0][0].variant).toBe('success');
  });

  it('lifts only once the organiser confirmed', async () => {
    ask.mockResolvedValueOnce(false);
    expect(await actions.lift('CRENEAUX')).toBe(false);
    expect(store.lift).not.toHaveBeenCalled();

    ask.mockResolvedValueOnce(true);
    expect(await actions.lift('CRENEAUX')).toBe(true);
    expect(store.lift).toHaveBeenCalledWith('CRENEAUX');
    expect(ask.mock.calls[0][0].title).toContain('Créneaux');
  });

  it('says a refused freeze rather than swallowing it', async () => {
    store.freeze.mockRejectedValueOnce(new Error('Refusé'));
    expect(await actions.freeze('COMPETENCES')).toBe(false);
    expect(notify.mock.calls[0][0]).toMatchObject({ variant: 'error', message: 'Refusé' });
  });
});
