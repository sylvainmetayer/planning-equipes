// Emptying an edition is one of the most destructive buttons of the admin
// interface. What is tested here is only that gate: the reset asks for the
// current edition's name to be typed back, it names that edition and what is
// left rather than promising something wider, and nothing is sent when the
// answer is not exactly it.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { EditionStore } from '../../core/edition.store';
import { GelReferentielStore } from '../../core/gel-referentiel.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { PlanningApi } from '../../core/api/planning-api';
import { EmptyEditionCard, CLEAR_KEYWORD } from './empty-edition-card';
import type { DemandeRecopie } from '../../shared/confirmation-recopie';
import type { Edition, EtatGel } from '../../core/models';

/** Reaches the protected handler the template binds the button to. */
type PageInternals = { empty: () => Promise<void> };

function page(): PageInternals {
  return TestBed.createComponent(EmptyEditionCard).componentInstance as unknown as PageInternals;
}

/** What `GET /api/editions/courant/gel` answers when two families are frozen. */
const FROZEN_STATES: EtatGel[] = [
  { famille: 'STANDS', libelle: 'Stands', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'CRENEAUX', libelle: 'Créneaux', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'TYPOLOGIES_EMPLACEMENTS', libelle: 'Typologies', fige: false, figeLe: null },
  { famille: 'COMPETENCES', libelle: 'Compétences', fige: false, figeLe: null },
];

describe('EmptyEditionCard', () => {
  const api = { get: vi.fn(), post: vi.fn() };
  const planningApi = { reset: vi.fn() };
  const recopie = { demander: vi.fn() };
  const instantane = { proposer: vi.fn() };
  const notifications = { notify: vi.fn() };
  const courant = vi.fn<() => Edition | null>();
  const rechargerEditions = vi.fn(async () => undefined);
  let gel: EtatGel[] = [];

  beforeEach(() => {
    api.get.mockReset();
    api.post.mockReset();
    recopie.demander.mockReset();
    instantane.proposer.mockReset();
    notifications.notify.mockReset();
    courant.mockReset();
    api.get.mockResolvedValue({ adminEmail: null });
    gel = [];
    api.get.mockImplementation(async (url: string) =>
      url === '/api/editions/courant/gel' ? gel : { adminEmail: null },
    );
    api.post.mockResolvedValue({ deleted: 0 });
    planningApi.reset.mockReset();
    planningApi.reset.mockResolvedValue({ deleted: 0 });
    recopie.demander.mockResolvedValue(true);
    instantane.proposer.mockResolvedValue(undefined);
    courant.mockReturnValue({ id: '2026', nom: 'Année 2026' } as Edition);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: api },
        { provide: PlanningApi, useValue: planningApi },
        { provide: EditionStore, useValue: { courant, reload: rechargerEditions } },
        { provide: NotificationService, useValue: notifications },
        { provide: ConfirmationRecopie, useValue: recopie },
        { provide: InstantaneAvantAction, useValue: instantane },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: ReferenceDataStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: ProblemesStore, useValue: { reloadFeasibility: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, activeJobDescription: () => '' },
        },
      ],
    });
  });

  function demande(): DemandeRecopie {
    return recopie.demander.mock.calls[0][0] as DemandeRecopie;
  }

  it('empties the edition once its name has been typed back', async () => {
    await page().empty();

    expect(demande().valeurAttendue).toBe('Année 2026');
    expect(instantane.proposer).toHaveBeenCalledOnce();
    expect(planningApi.reset).toHaveBeenCalledOnce();
  });

  // The whole point of the guard: a refused transcription must leave the
  // database, and the snapshot offer that precedes the wipe, untouched.
  it('sends nothing when the confirmation was refused', async () => {
    recopie.demander.mockResolvedValue(false);

    await page().empty();

    expect(instantane.proposer).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
  });

  // The reset deletes where `edition_id` matches: a message hinting at the
  // whole instance would scare the user out of a safe operation, and one
  // hinting at nothing would let them empty the wrong edition.
  it('names the edition it is about to empty, and says what is left', async () => {
    await page().empty();

    expect(demande().title).toContain('Année 2026');
    expect(demande().message).toContain('Année 2026');
    expect(demande().message).toContain('les autres éditions');
    expect(demande().message).toContain("l'instance");
    // An edition is what it empties, and nothing wider.
    expect(`${demande().title} ${demande().message}`).not.toContain('base de données');
  });

  // Le shell recharge les éditions sans attendre : arriver ici par un lien
  // direct peut précéder la réponse. On recharge plutôt que de dégrader la
  // recopie, sans quoi cinq lettres suffiraient à vider une vraie édition.
  it('reloads the editions before asking, rather than degrading the transcription', async () => {
    courant.mockReturnValueOnce(null).mockReturnValue({ id: 'e1', nom: 'Année 2026' } as Edition);

    await page().empty();

    expect(rechargerEditions).toHaveBeenCalled();
    expect(demande().valeurAttendue).toBe('Année 2026');
  });

  it('falls back to a keyword when the edition stays unknown after the reload', async () => {
    courant.mockReturnValue(null);

    await page().empty();

    expect(demande().valeurAttendue).toBe(CLEAR_KEYWORD);
    expect(demande().message).toContain("l'édition courante");
  });

  // Un nom vide ferait exiger une valeur vide, que PromptDialog refuse : le
  // reset deviendrait inatteignable. Un dump rejoué à la main peut le produire.
  it('treats a blank edition name as no name at all', async () => {
    courant.mockReturnValue({ id: 'e1', nom: '   ' } as Edition);

    await page().empty();

    expect(demande().valeurAttendue).toBe(CLEAR_KEYWORD);
  });

  // The solver lock comes first: no point asking for a transcription of
  // something the server will refuse anyway.
  it('asks for nothing while a solve is running', async () => {
    TestBed.overrideProvider(SolverJobService, {
      useValue: {
        solverBusy: () => true,
        activeJobDescription: () => 'Une résolution est en cours.',
      },
    });

    await page().empty();

    expect(recopie.demander).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
    expect(notifications.notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'warning' }),
    );
  });

  // The server refuses to empty an edition while any family of its referential
  // is frozen: no transcription asked, no snapshot offered, nothing sent.
  it('asks for nothing while a family of the referential is frozen', async () => {
    gel = FROZEN_STATES;
    const internals = page();
    await TestBed.inject(GelReferentielStore).ensureLoaded();

    await internals.empty();

    expect(recopie.demander).not.toHaveBeenCalled();
    expect(instantane.proposer).not.toHaveBeenCalled();
    expect(planningApi.reset).not.toHaveBeenCalled();
  });
});
