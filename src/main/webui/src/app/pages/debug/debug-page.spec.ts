// Emptying the database is the most destructive button of the admin interface,
// and it is one click away from a diagnostic screen people open to look at a
// score. What is tested here is only that gate: the reset asks for the current
// edition's name to be typed back, it names that edition rather than promising
// something wider, and nothing is sent when the answer is not exactly it.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { DebugPage, MOT_CLE_VIDER } from './debug-page';
import type { DemandeRecopie } from '../../shared/confirmation-recopie';
import type { Edition } from '../../core/models';

/** Reaches the protected handler the template binds the button to. */
type PageInternals = { onResetDatabase: () => Promise<void> };

describe('DebugPage reset', () => {
  const api = { get: vi.fn(), post: vi.fn() };
  const recopie = { demander: vi.fn() };
  const instantane = { proposer: vi.fn() };
  const notifications = { notify: vi.fn() };
  const courant = vi.fn<() => Edition | null>();

  beforeEach(() => {
    api.get.mockReset();
    api.post.mockReset();
    recopie.demander.mockReset();
    instantane.proposer.mockReset();
    notifications.notify.mockReset();
    courant.mockReset();
    api.get.mockResolvedValue({ adminEmail: null });
    api.post.mockResolvedValue({ deleted: 0 });
    recopie.demander.mockResolvedValue(true);
    instantane.proposer.mockResolvedValue(undefined);
    courant.mockReturnValue({ id: '2026', nom: 'Année 2026' } as Edition);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: api },
        { provide: EditionStore, useValue: { courant } },
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
          useValue: { solverBusy: () => false, activeJobDescription: () => '' }
        }
      ]
    });
  });

  function page(): PageInternals {
    return TestBed.createComponent(DebugPage).componentInstance as unknown as PageInternals;
  }

  function demande(): DemandeRecopie {
    return recopie.demander.mock.calls[0][0] as DemandeRecopie;
  }

  it('empties the database once the edition name has been typed back', async () => {
    await page().onResetDatabase();

    expect(demande().valeurAttendue).toBe('Année 2026');
    expect(instantane.proposer).toHaveBeenCalledOnce();
    expect(api.post).toHaveBeenCalledWith('/api/planning/reset', {});
  });

  // The whole point of the guard: a refused transcription must leave the
  // database, and the snapshot offer that precedes the wipe, untouched.
  it('sends nothing when the confirmation was refused', async () => {
    recopie.demander.mockResolvedValue(false);

    await page().onResetDatabase();

    expect(instantane.proposer).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
  });

  // The reset deletes where `edition_id` matches: a message hinting at the
  // whole instance would scare the user out of a safe operation, and one
  // hinting at nothing would let them empty the wrong edition.
  it('names the edition it is about to empty, and clears the other ones', async () => {
    await page().onResetDatabase();

    expect(demande().message).toContain('Année 2026');
    expect(demande().message).toContain('Les autres éditions ne sont pas touchées');
  });

  it('falls back to a keyword when no edition is loaded, without naming one', async () => {
    courant.mockReturnValue(null);

    await page().onResetDatabase();

    expect(demande().valeurAttendue).toBe(MOT_CLE_VIDER);
    expect(demande().message).toContain("l'édition courante");
  });

  // The solver lock comes first: no point asking for a transcription of
  // something the server will refuse anyway.
  it('asks for nothing while a solve is running', async () => {
    TestBed.overrideProvider(SolverJobService, {
      useValue: { solverBusy: () => true, activeJobDescription: () => 'Une résolution est en cours.' }
    });

    await page().onResetDatabase();

    expect(recopie.demander).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
    expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'warning' }));
  });
});
