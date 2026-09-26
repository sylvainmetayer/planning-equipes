// The gate itself, tested apart from the page that calls it: an ordinary rule
// switches off without a dialog, a protected one only if the administrator
// confirms — and a dismissed dialog (Escape, backdrop) counts as a refusal,
// not as a confirmation.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintView } from '../../core/models';
import { LegalDisableConfirmService, LegalDisableDialog } from './legal-disable-dialog';

function contrainte(overrides: Partial<ConstraintView> = {}): ConstraintView {
  return {
    name: 'travailDeNuitInterditPourMineur',
    libelleCourt: 'Travail de nuit des mineurs',
    niveau: 'HARD',
    categorie: 'Légal (mineurs)',
    description: 'Pas de travail de nuit pour un mineur (art. L3163-1).',
    actif: true,
    protegee: true,
    legale: true,
    dosable: false,
    poids: 1,
    score: null,
    matchCount: null,
    violations: [],
    postesEvalues: null,
    plancher: null,
    references: [],
    ...overrides,
  };
}

describe('LegalDisableConfirmService', () => {
  const dialog = { open: vi.fn() };
  let service: LegalDisableConfirmService;

  beforeEach(() => {
    dialog.open.mockReset();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: MatDialog, useValue: dialog }],
    });
    service = TestBed.inject(LegalDisableConfirmService);
  });

  function answers(result: boolean | undefined): void {
    dialog.open.mockReturnValue({ afterClosed: () => of(result) });
  }

  it('lets an ordinary rule through without opening anything', async () => {
    await expect(service.allowsDisabling(contrainte({ protegee: false }))).resolves.toBe(true);

    expect(dialog.open).not.toHaveBeenCalled();
  });

  /**
   * Issue #595 ships `mineurNecessiteEncadrementMajeur` off. Putting a rule
   * back where the catalogue shipped it is not taking back a commitment, so it
   * asks nothing — and the badge on its row is gated on the same predicate, so
   * the screen never promises a ceremony that will not happen.
   */
  it('asks nothing about a rule the catalogue itself ships switched off', async () => {
    await expect(service.allowsDisabling(contrainte({ activeByDefault: false }))).resolves.toBe(
      true,
    );

    expect(dialog.open).not.toHaveBeenCalled();
  });

  // An older payload carries no `activeByDefault` at all: the confirmation must
  // keep applying on it rather than fall silent.
  it('still asks when the payload does not say what the default is', async () => {
    answers(true);

    await expect(service.allowsDisabling(contrainte({ activeByDefault: undefined }))).resolves.toBe(
      true,
    );

    expect(dialog.open).toHaveBeenCalledOnce();
  });

  it('names the rule and the article in the dialog it opens', async () => {
    answers(true);

    await expect(service.allowsDisabling(contrainte())).resolves.toBe(true);

    expect(dialog.open).toHaveBeenCalledOnce();
    expect(dialog.open.mock.calls[0][0]).toBe(LegalDisableDialog);
    expect(dialog.open.mock.calls[0][1].data).toEqual({
      libelle: 'Travail de nuit des mineurs',
      description: 'Pas de travail de nuit pour un mineur (art. L3163-1).',
      categorie: 'Légal (mineurs)',
      legale: true,
    });
  });

  // The meal break is protected but is not the law: the dialog must be able to
  // say so, so the flag has to reach it rather than be inferred from the
  // category label on the client side.
  it('marks a protected rule that is not founded in law', async () => {
    answers(true);

    await expect(
      service.allowsDisabling(
        contrainte({
          name: 'coupureRepasObligatoire',
          categorie: 'Organisation (repas)',
          description: 'Une coupure repas est due de part et d’autre de chaque fenêtre repas.',
          legale: false,
        }),
      ),
    ).resolves.toBe(true);

    expect(dialog.open.mock.calls[0][1].data.legale).toBe(false);
  });

  it('refuses when the dialog is cancelled', async () => {
    answers(false);

    await expect(service.allowsDisabling(contrainte())).resolves.toBe(false);
  });

  // Escape or a click on the backdrop closes with no value at all: anything
  // but an explicit confirmation leaves the rule in place.
  it('refuses when the dialog is dismissed without an answer', async () => {
    answers(undefined);

    await expect(service.allowsDisabling(contrainte())).resolves.toBe(false);
  });
});
