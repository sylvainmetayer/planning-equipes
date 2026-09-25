// The consigne form reads the stands for the first date and the band, and
// re-reads them whenever either moves. Two requests in flight is the ordinary
// case — a date picked, then corrected — and the reply to the first must not
// land over the second: the list is a `resource()` keyed on the three, which
// drops a reply to parameters the form has moved past.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConsignesApi } from '../../core/api/consignes-api';
import { LigneStandConsigne, PreselectionConsigne, Stand } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConsigneFormData, ConsigneFormDialog } from './consigne-form-dialog';
import { StandForm } from './consignes';
import { noDraftStorage, fakeDialogRef } from '../../core/testing/brouillon';

/** What the spec drives on the component, without the template's Material controls. */
interface DialogInternals {
  stands: () => StandForm[];
  chargementStands: () => boolean;
  erreurs: () => string[];
  dates: () => string[];
  onDates(dates: string[]): void;
}

function ligne(standId: string): LigneStandConsigne {
  return {
    standId,
    standNom: `Stand ${standId}`,
    minutesPerdues: 120,
    effectifHerite: 2,
    exceptionDatee: false,
    motif: null,
    preCoche: true,
    ouvertures: [],
  };
}

function preselection(date: string, ...standIds: string[]): PreselectionConsigne {
  return { date, creneauxDuJour: 2, stands: standIds.map(ligne) };
}

/** Lets the resource issue its request, or take a reply in: a macrotask, then a render. */
async function settle(fixture: ComponentFixture<ConsigneFormDialog>): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('ConsigneFormDialog — the stands list', () => {
  let fixture: ComponentFixture<ConsigneFormDialog>;
  let dialog: DialogInternals;
  let requests: Array<{ date: string; resolve: (value: PreselectionConsigne) => void }>;

  function mount(datesInitiales: string[], stands: Stand[] = []): void {
    requests = [];
    const api = {
      preselection: vi.fn(
        (demande: { date: string }) =>
          new Promise<PreselectionConsigne>((resolve) => {
            requests.push({ date: demande.date, resolve });
          }),
      ),
    };
    const data: ConsigneFormData = {
      mode: 'poser',
      consigne: null,
      datesInitiales,
      datesCandidates: ['2026-07-11', '2026-07-12'],
      prereglages: [],
      stands,
      typologies: [],
      emplacements: [],
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ConsignesApi, useValue: api },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: MatDialogRef, useValue: fakeDialogRef(vi.fn()) },
        ...noDraftStorage(),
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    });
    fixture = TestBed.createComponent(ConsigneFormDialog);
    dialog = fixture.componentInstance as unknown as DialogInternals;
  }

  beforeEach(() => mount(['2026-07-11']));

  it('ignores the reply to a request the form has moved past', async () => {
    fixture.detectChanges();
    await settle(fixture);
    expect(requests.map((request) => request.date)).toEqual(['2026-07-11']);

    dialog.onDates(['2026-07-12']);
    fixture.detectChanges();
    await settle(fixture);
    expect(requests.map((request) => request.date)).toEqual(['2026-07-11', '2026-07-12']);

    // The second reply lands first, then the first one arrives late.
    requests[1].resolve(preselection('2026-07-12', 'B'));
    await settle(fixture);
    expect(dialog.stands().map((stand) => stand.standId)).toEqual(['B']);
    expect(dialog.chargementStands()).toBe(false);

    requests[0].resolve(preselection('2026-07-11', 'A'));
    await settle(fixture);
    expect(dialog.stands().map((stand) => stand.standId)).toEqual(['B']);
  });

  it('keeps what was chosen for a stand across a re-read', async () => {
    fixture.detectChanges();
    await settle(fixture);
    requests[0].resolve(preselection('2026-07-11', 'A', 'B'));
    await settle(fixture);
    expect(dialog.stands().map((stand) => stand.coche)).toEqual([true, true]);

    (
      fixture.componentInstance as unknown as { patchStand(id: string, p: object): void }
    ).patchStand('A', { coche: false });
    dialog.onDates(['2026-07-12']);
    await settle(fixture);
    requests[1].resolve(preselection('2026-07-12', 'A', 'C'));
    await settle(fixture);

    expect(dialog.stands().map((stand) => [stand.standId, stand.coche])).toEqual([
      ['A', false],
      ['C', true],
    ]);
  });

  it('names the preview stands by their name, an unknown id kept as-is', () => {
    mount(['2026-07-11'], [{ id: 'S1', nom: 'Bourse aux jeux' } as Stand]);
    const texte = (
      fixture.componentInstance as unknown as { standsText(ids: string[]): string }
    ).standsText(['S1', 'S9']);
    expect(texte).toBe('Bourse aux jeux, S9');
  });

  it('does not tick a deep-linked date the form does not offer, and says so', async () => {
    mount(['2026-07-09']);
    fixture.detectChanges();
    await settle(fixture);

    expect(dialog.dates()).toEqual([]);
    expect(dialog.erreurs()).toContain('DATES');
    expect(requests).toEqual([]);
  });
});
