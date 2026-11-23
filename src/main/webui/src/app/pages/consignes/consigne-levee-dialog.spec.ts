// The lifting preview turns the risk of ADR 0089 into sentences: the count,
// the days to unlock with how many they free, and the people they do not.
// Each sentence is its own message, so the space between them is the
// template's to keep.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { ConsignesApi } from '../../core/api/consignes-api';
import { ApercuLeveeConsigne } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ConsigneLeveeData, ConsigneLeveeDialog } from './consigne-levee-dialog';

const DATE = '2027-02-05';

function apercu(animateursSansJourLibre: number, animateursLiberes: number): ApercuLeveeConsigne {
  return {
    date: DATE,
    sousConsigne: true,
    creneauxARetirer: [],
    validationRetiree: false,
    animateursSurLesCreneauxRetires: 0,
    animateursSansJourLibre,
    animateursLiberes,
    joursADeverrouiller: animateursLiberes > 0 ? ['2027-02-04'] : [],
  };
}

async function previewed(reply: ApercuLeveeConsigne): Promise<string> {
  const api = fakeOf<ConsignesApi>({ apercuLevee: vi.fn(async () => [reply]) });
  const data: ConsigneLeveeData = { datesLevables: [DATE], datesInitiales: [DATE] };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideFake(ConsignesApi, api),
      provideFake(ReferenceCrudService, fakeOf<ReferenceCrudService>({ reportError: vi.fn() })),
      provideFake(SolverJobService, fakeOf<SolverJobService>({ editingLocked: signal(false) })),
      { provide: MatDialogRef, useValue: { close: vi.fn() } },
      { provide: MAT_DIALOG_DATA, useValue: data },
    ],
  });
  const fixture = TestBed.createComponent(ConsigneLeveeDialog);
  await fixture.componentInstance['preview']();
  fixture.detectChanges();
  await fixture.whenStable();
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

describe('ConsigneLeveeDialog — the frozen week', () => {
  it('says the risk, the day to unlock and how many it frees, a space between the sentences', async () => {
    const text = await previewed(apercu(3, 2));

    expect(text).toContain(
      '3 animateurs disponibles ce jour-là travaillent déjà six jours de la semaine sur des journées figées',
    );
    expect(text).toMatch(/tenue\. Déverrouiller \S+/);
    expect(text).toContain('en rendrait 2 au calcul.');
    expect(text).toMatch(/au calcul\. 1 reste retenu par un jour passé/);
  });

  it('speaks of one person in the singular', async () => {
    const text = await previewed(apercu(1, 0));

    expect(text).toContain(
      '1 animateur disponible ce jour-là travaille déjà six jours de la semaine sur des journées figées',
    );
    expect(text).not.toContain('Déverrouiller');
    expect(text).toMatch(/tenue\. 1 reste retenu par un jour passé ou un autre verrou\./);
  });

  it('speaks of several in the plural, and names no day when nothing reopenable holds them', async () => {
    const text = await previewed(apercu(2, 0));

    expect(text).toContain('2 animateurs disponibles ce jour-là travaillent déjà');
    expect(text).not.toContain('Déverrouiller');
    expect(text).toMatch(/tenue\. 2 restent retenus par un jour passé ou un autre verrou\./);
  });

  it('stays silent when the week is not frozen full', async () => {
    expect(await previewed(apercu(0, 0))).not.toContain('journées figées');
  });
});
