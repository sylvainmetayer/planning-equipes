// The dialog grants one right to one account. What is pinned here: a stand
// manager's expiry is offered from the edition's last day (and a typed date
// stays), the stands are those of the edition picked, the form says what the
// server would refuse before sending it, and a refusal that still comes stays
// in the dialog.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComptesApi } from '../../core/api/comptes-api';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { StandsApi } from '../../core/api/stands-api';
import { Compte, DiagnosticGrille, Edition, Stand } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { GrantRightData, GrantRightDialog } from './grant-right-dialog';

const COMPTE: Compte = {
  id: 'c1',
  email: 'rita@example.org',
  nom: null,
  sujet: null,
  creeLe: '2026-01-01T00:00:00Z',
  derniereConnexionLe: null,
  desactiveLe: null,
  habilitations: [],
};
const EDITIONS: Edition[] = [{ id: '2099', nom: 'Année 2099', active: true, creeLe: null }];
const STAND_B: Stand = {
  id: 'B',
  nom: 'Billetterie',
  typologiesProposees: [],
  effectifMin: 1,
  effectifMax: 2,
  reserveMajeurs: false,
  premium: false,
  niveauEffort: 'NORMAL',
  emplacement: null,
  indisponibilites: [],
  ouvertures: [],
  horaires: [],
};
const STAND_A: Stand = { ...STAND_B, id: 'A', nom: 'Accueil' };
const GRILLE: DiagnosticGrille = {
  nombreCreneaux: 4,
  premiereDate: '2099-06-08',
  derniereDate: '2099-06-10',
  contientCouverturePause: false,
  explication: '',
  joursFeries: [],
};

describe('GrantRightDialog', () => {
  const close = vi.fn();
  const comptesApi = fakeOf<ComptesApi>({ grant: () => Promise.resolve(COMPTE) });
  const standsApi = fakeOf<StandsApi>({ listInEdition: () => Promise.resolve([STAND_B, STAND_A]) });
  const creneauxApi = fakeOf<CreneauxApi>({ diagnosticInEdition: () => Promise.resolve(GRILLE) });

  async function monter(data: Partial<GrantRightData> = {}) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: MatDialogRef, useValue: { close } },
        {
          provide: MAT_DIALOG_DATA,
          useValue: { compte: COMPTE, editions: EDITIONS, currentEditionId: '2099', ...data },
        },
        provideFake(ComptesApi, comptesApi),
        provideFake(StandsApi, standsApi),
        provideFake(CreneauxApi, creneauxApi),
      ],
    });
    const fixture = TestBed.createComponent(GrantRightDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, dialog: fixture.componentInstance };
  }

  async function devenirResponsable(
    fixture: Awaited<ReturnType<typeof monter>>['fixture'],
    dialog: GrantRightDialog,
  ) {
    dialog['setRole']('RESPONSABLE_STAND');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    close.mockClear();
    comptesApi.grant.mockClear();
    standsApi.listInEdition.mockClear();
    creneauxApi.diagnosticInEdition.mockClear();
  });

  it('grants an HR right for every edition, with no stand and no date', async () => {
    const { dialog } = await monter();

    await dialog['save']();

    expect(comptesApi.grant).toHaveBeenCalledExactlyOnceWith('c1', {
      role: 'RH',
      editionId: null,
      expireLe: null,
      standIds: [],
      nominatif: null,
    });
    expect(close).toHaveBeenCalledExactlyOnceWith(COMPTE);
  });

  it("offers a stand manager the edition's last day plus thirty, in the current edition", async () => {
    const { fixture, dialog } = await monter();

    await devenirResponsable(fixture, dialog);

    expect(dialog['draft']().editionId).toBe('2099');
    expect(creneauxApi.diagnosticInEdition).toHaveBeenCalledWith('2099');
    expect(dialog['draft']().expiryDate).toBe('2099-07-10');
    expect(dialog['standOptions']().map((stand) => stand.nom)).toEqual(['Accueil', 'Billetterie']);
  });

  it('refuses a stand manager without a stand, and says it before sending', async () => {
    const { fixture, dialog } = await monter();
    await devenirResponsable(fixture, dialog);

    await dialog['save']();

    expect(comptesApi.grant).not.toHaveBeenCalled();
    expect(dialog['submitted']()).toBe(true);
    expect(dialog['errors']().length).toBeGreaterThan(0);
  });

  it('grants a stand manager the stands picked, with the override chosen', async () => {
    const { fixture, dialog } = await monter();
    await devenirResponsable(fixture, dialog);
    dialog['setStands'](['A']);
    dialog['setNominatif']('noms');

    await dialog['save']();

    expect(comptesApi.grant).toHaveBeenCalledOnce();
    const [, demande] = comptesApi.grant.mock.calls[0];
    expect(demande).toMatchObject({
      role: 'RESPONSABLE_STAND',
      editionId: '2099',
      standIds: ['A'],
      nominatif: true,
    });
    expect(demande.expireLe).not.toBeNull();
  });

  it('takes the filled-in date back when the role goes back to HR, but keeps a typed one', async () => {
    const { fixture, dialog } = await monter();
    await devenirResponsable(fixture, dialog);
    expect(dialog['draft']().expiryDate).not.toBe('');

    dialog['setRole']('RH');
    expect(dialog['draft']().expiryDate).toBe('');

    dialog['setRole']('RESPONSABLE_STAND');
    dialog['setExpiry']('2099-12-31');
    dialog['setRole']('RH');
    expect(dialog['draft']().expiryDate).toBe('2099-12-31');
  });

  it('drops the stands picked when the edition changes', async () => {
    const { fixture, dialog } = await monter();
    await devenirResponsable(fixture, dialog);
    dialog['setStands'](['A']);

    dialog['setEdition'](null);

    expect(dialog['draft']().standIds).toEqual([]);
  });

  it('stays open on a refusal of the server, and says it', async () => {
    comptesApi.grant.mockRejectedValueOnce(
      new Error('Stand(s) inconnu(s) dans l’édition 2099 : A'),
    );
    const { dialog } = await monter();

    await dialog['save']();

    expect(close).not.toHaveBeenCalled();
    expect(dialog['serverError']()).toContain('inconnu');
    expect(dialog['busy']()).toBe(false);
  });
});
