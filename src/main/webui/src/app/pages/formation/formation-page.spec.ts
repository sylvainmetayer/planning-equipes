// The « À former » tab over a hand-built plan: what it shows for each
// typologie, and what it says when there is nothing to list.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanFormation } from '../../core/models';
import { FormationPage } from './formation-page';

const PLAN: PlanFormation = {
  planPersiste: true,
  aucuneCompetence: false,
  aucunAnimateur: false,
  typologies: [
    {
      typologie: 'ESCAPE',
      label: 'Escape game',
      ninja: false,
      manque: 1,
      specialistes: 2,
      competencesRares: 2,
      groupesSansSpecialiste: 0,
      postesIrremplacables: 1,
      joursTension: ['2026-07-10', '2026-07-11'],
      candidats: [
        {
          animateurId: 'bob',
          nom: 'Bob Martin',
          niveau: 'AUTONOME',
          souhait: true,
          joursTensionDisponibles: 2,
        },
        {
          animateurId: 'eve',
          nom: 'Ève Petit',
          niveau: 'DEBUTANT',
          souhait: false,
          joursTensionDisponibles: 0,
        },
      ],
    },
    {
      typologie: 'QUIZ',
      label: 'Quiz',
      ninja: false,
      manque: 0,
      specialistes: 1,
      competencesRares: 1,
      groupesSansSpecialiste: 1,
      postesIrremplacables: 0,
      joursTension: ['2026-07-10'],
      candidats: [],
    },
  ],
};

async function rendre(plan: PlanFormation): Promise<{
  fixture: ComponentFixture<FormationPage>;
  api: { trainingPlan: ReturnType<typeof vi.fn>; exportTrainingPlan: ReturnType<typeof vi.fn> };
}> {
  const api = {
    trainingPlan: vi.fn(async () => plan),
    exportTrainingPlan: vi.fn(async () => 'plan-formation.csv téléchargé'),
  };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: AnalysesApi, useValue: api },
    ],
  });
  const fixture = TestBed.createComponent(FormationPage);
  await fixture.whenStable();
  return { fixture, api };
}

describe('FormationPage', () => {
  it('lists each typologie in shortage with its deficit, its tension days and its ranked candidates', async () => {
    const { fixture } = await rendre(PLAN);
    const racine = fixture.nativeElement as HTMLElement;

    const sections = Array.from(racine.querySelectorAll('.formation-typologie'));
    expect(sections.map((section) => section.querySelector('h2')!.textContent!.trim())).toEqual([
      'Escape game',
      'Quiz',
    ]);
    expect(sections[0].querySelector('.formation-deficit')!.textContent).toContain(
      'manque 1 animateur(s) au besoin',
    );
    expect(sections[0].querySelector('.formation-jours')!.textContent).toContain('10/07');
    const lignes = Array.from(sections[0].querySelectorAll('tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => td.textContent!.trim()),
    );
    expect(lignes).toEqual([
      ['Bob Martin', 'Autonome', 'Oui', '2 / 2'],
      ['Ève Petit', 'Débutant', 'Non', '0 / 2'],
    ]);
    expect(sections[1].querySelector('.formation-recrutement')!.textContent).toContain(
      'Aucun candidat : recrutement.',
    );
  });

  it('says so when nobody holds any competence, and points at the grid', async () => {
    const { fixture } = await rendre({ ...PLAN, typologies: [], aucuneCompetence: true });
    const racine = fixture.nativeElement as HTMLElement;

    expect(racine.textContent).toContain('Aucune appréciation n');
    expect(racine.querySelector('a[href="/competences"]')).not.toBeNull();
  });

  it('warns that only the staffing need speaks without a persisted plan', async () => {
    const { fixture } = await rendre({ ...PLAN, planPersiste: false });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.formation-degrade'),
    ).not.toBeNull();
  });

  it('exports the tab as a CSV', async () => {
    const { fixture, api } = await rendre(PLAN);
    const bouton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((each) => each.textContent!.includes('Exporter'))!;

    bouton.click();
    await fixture.whenStable();

    expect(api.exportTrainingPlan).toHaveBeenCalled();
  });
});
