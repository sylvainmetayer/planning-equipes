// Perimeter dialog of the incremental re-solve. It hands the solver page a
// `PerimetreReplanification`, and the two things that can go wrong there are
// both invisible from the outside: a cancel that is mistaken for "re-solve
// everything I did not pick" (it must produce no perimeter at all), and lists
// left in referential order — an operator hunting for a name among 153
// animateurs sorted by insertion is an operator who picks the wrong one.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { describe, expect, it, vi } from 'vitest';
import { Animateur, Creneau, PerimetreReplanification, Stand } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReplanificationDialog } from './replanification-dialog';

function animateur(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

function stand(id: string, nom: string): Stand {
  return {
    id,
    nom,
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
}

const CRENEAUX: Creneau[] = [
  { id: 1, jour: 2, date: '2026-07-15', heureDebut: '10:00', heureFin: '12:00' },
  { id: 2, jour: 1, date: '2026-07-14', heureDebut: '14:00', heureFin: '19:00' },
  { id: 3, jour: 1, date: '2026-07-14', heureDebut: '10:00', heureFin: '12:00' },
];

function monter(depuis: string | null = null) {
  const close = vi.fn();
  const changesSince = vi.fn(async () => ({ total: 0, parEntite: [], dernieres: [] }));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: ReferenceDataStore,
        useValue: {
          animateurs: signal([
            animateur('a1', 'Marcel', 'Proust'),
            animateur('a2', 'Amélie', 'Nothomb'),
          ]),
          stands: signal([stand('s1', 'Loup-Garou'), stand('s2', 'Dixit')]),
          creneaux: signal(CRENEAUX),
        },
      },
      { provide: MatDialogRef, useValue: { close } },
      provideRouter([]),
      { provide: AnalysesApi, useValue: { changesSince } },
      ...(depuis === null ? [] : [{ provide: MAT_DIALOG_DATA, useValue: { depuis } }]),
    ],
  });
  return { fixture: TestBed.createComponent(ReplanificationDialog), close, changesSince };
}

function racine(fixture: ComponentFixture<ReplanificationDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** Opens a `mat-select` and returns the visible options of its overlay. */
async function options(
  fixture: ComponentFixture<ReplanificationDialog>,
  name: string,
): Promise<string[]> {
  const select = racine(fixture).querySelector(`mat-select[name="${name}"]`) as HTMLElement;
  (select.querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
  await fixture.whenStable();
  return Array.from(document.querySelectorAll('mat-option')).map((each) =>
    each.textContent!.trim(),
  );
}

async function choisir(
  fixture: ComponentFixture<ReplanificationDialog>,
  name: string,
  libelle: string,
) {
  const disponibles = await options(fixture, name);
  expect(disponibles, `option « ${libelle} » absente`).toContain(libelle);
  const option = Array.from(document.querySelectorAll('mat-option')).find(
    (each) => each.textContent!.trim() === libelle,
  ) as HTMLElement;
  option.click();
  await fixture.whenStable();
}

describe('ReplanificationDialog', () => {
  it('sorts the animateurs by name, not in referential order', async () => {
    const { fixture } = monter();
    await fixture.whenStable();

    expect(await options(fixture, 'animateurIds')).toEqual(['Amélie Nothomb', 'Marcel Proust']);
  });

  it('sorts the stands by name', async () => {
    const { fixture } = monter();
    await fixture.whenStable();

    expect(await options(fixture, 'standIds')).toEqual(['Dixit', 'Loup-Garou']);
  });

  it('offers each event day once, in chronological order and spelled out', async () => {
    const { fixture } = monter();
    await fixture.whenStable();

    // Three créneaux over two days, and the day is what is re-planned.
    expect(await options(fixture, 'jours')).toEqual(['mardi 14 juillet', 'mercredi 15 juillet']);
  });

  it('explains that an empty perimeter is the automatic one, and stops saying it once something is picked', async () => {
    const { fixture } = monter();
    await fixture.whenStable();
    expect(racine(fixture).querySelector('.calendar-meta')!.textContent!).toContain(
      'Périmètre automatique',
    );

    await choisir(fixture, 'standIds', 'Dixit');

    expect(racine(fixture).querySelector('.calendar-meta')).toBeNull();
  });

  it('hands back exactly what the operator picked', async () => {
    const { fixture, close } = monter();
    await fixture.whenStable();

    await choisir(fixture, 'animateurIds', 'Marcel Proust');
    await choisir(fixture, 'jours', 'mardi 14 juillet');
    await choisir(fixture, 'standIds', 'Dixit');
    (racine(fixture).querySelectorAll('mat-dialog-actions button')[1] as HTMLButtonElement).click();

    expect(close).toHaveBeenCalledWith({
      animateurIds: ['a1'],
      jours: ['2026-07-14'],
      standIds: ['s2'],
    } satisfies PerimetreReplanification);
  });

  it('hands back an empty perimeter — not a cancel — when nothing was picked', async () => {
    const { fixture, close } = monter();
    await fixture.whenStable();

    (racine(fixture).querySelectorAll('mat-dialog-actions button')[1] as HTMLButtonElement).click();

    // The solver page treats a falsy result as "the user gave up": an empty
    // perimeter is a valid answer and must not be confused with one.
    expect(close).toHaveBeenCalledWith({ animateurIds: [], jours: [], standIds: [] });
  });

  it('closes with nothing at all on cancel', async () => {
    const { fixture, close } = monter();
    await fixture.whenStable();

    (racine(fixture).querySelectorAll('mat-dialog-actions button')[0] as HTMLButtonElement).click();
    await fixture.whenStable();

    // `mat-dialog-close` with no value closes on `''`; what the solver page
    // needs is only that it is falsy, so it starts nothing.
    expect(close).toHaveBeenCalledOnce();
    expect(close.mock.calls[0][0]).toBeFalsy();
  });

  // « Corriger » shows what changed before it asks what else to re-open.
  it('opens on what changed since the plan, read from the moment it was solved', async () => {
    const { fixture, changesSince } = monter('2026-07-10T08:00:00Z');
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(racine(fixture).textContent).toContain('Aucune donnée modifiée depuis ce plan');
    });

    expect(changesSince).toHaveBeenCalledWith('2026-07-10T08:00:00Z');
    expect(racine(fixture).textContent).toContain('Ce qui a changé depuis le plan');
  });

  it('says nothing of changes without a plan to count them from', async () => {
    const { fixture, changesSince } = monter('');
    await fixture.whenStable();

    expect(changesSince).not.toHaveBeenCalled();
    expect(racine(fixture).textContent).not.toContain('Ce qui a changé');
  });
});
