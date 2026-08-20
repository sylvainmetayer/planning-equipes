// Badge visibility only: the component is created but never rendered, so this
// stays a logic test (the project favours those over full DOM rendering).

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { AnimateursPage } from './animateurs-page';
import type { Animateur, CauseInfaisabilite, FeasibilityReport } from '../../core/models';

function animateur(id: string, joursIndisponibles: string[]): Animateur {
  return {
    id,
    prenom: id,
    nom: id.toUpperCase(),
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles
  };
}

function cause(severite: CauseInfaisabilite['severite'], date: string): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite,
    message: `Manque d'animateurs le ${date}.`,
    creneauId: '12',
    date,
    heureDebut: '12:30',
    heureFin: '15:30',
    standIds: ['tir'],
    demande: 6,
    capacite: 4,
    manque: 2
  };
}

function report(causes: CauseInfaisabilite[]): FeasibilityReport {
  return {
    feasible: false,
    manqueAnimateurs: 2,
    causes,
    totalCauses: causes.length,
    message: 'Planning non réalisable en l’état.'
  };
}

/** Reaches the protected computed the template binds to. */
type PageInternals = { alerteParAnimateurId: Signal<Map<string, string>> };

describe('AnimateursPage alert badges', () => {
  let referenceData: ReferenceDataStore;
  let problemes: ProblemesStore;
  const api = { get: vi.fn(async () => report([])) };

  beforeEach(() => {
    api.get.mockReset();
    api.get.mockResolvedValue(report([]));
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: { open: vi.fn() } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
    problemes = TestBed.inject(ProblemesStore);
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(AnimateursPage).componentInstance as unknown as PageInternals;
  }

  it('flags nobody while no diagnostic is loaded', () => {
    referenceData.animateurs.set([animateur('alice', ['2026-08-01'])]);
    expect(createPage().alerteParAnimateurId().size).toBe(0);
  });

  it('flags an animateur unavailable on a day carrying a CRITIQUE cause', async () => {
    referenceData.animateurs.set([animateur('alice', ['2026-08-01']), animateur('bob', ['2026-08-05'])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect([...page.alerteParAnimateurId().keys()]).toEqual(['alice']);
    expect(page.alerteParAnimateurId().get('alice')).toContain('2026-08-01');
    expect(page.alerteParAnimateurId().get('alice')).toContain("Manque d'animateurs");
  });

  it('ignores a day that is only ELEVE', async () => {
    referenceData.animateurs.set([animateur('alice', ['2026-08-01'])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('ELEVE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect(page.alerteParAnimateurId().size).toBe(0);
  });

  it('ignores an animateur available on every critical day', async () => {
    referenceData.animateurs.set([animateur('alice', [])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect(page.alerteParAnimateurId().size).toBe(0);
  });

  it('recomputes when the roster changes', async () => {
    referenceData.animateurs.set([]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();
    expect(page.alerteParAnimateurId().size).toBe(0);

    referenceData.animateurs.set([animateur('carole', ['2026-08-01'])]);
    expect(page.alerteParAnimateurId().has('carole')).toBe(true);
  });
});
