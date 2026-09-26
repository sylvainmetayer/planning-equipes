// « Par typologie », the Planning page's third axis: one table over the
// report, narrowed by the page's filters, every figure a way somewhere.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { LigneTypologie } from '../../core/models';
import { PlanningTypologieView } from './planning-typologie-vue';

function ligne(typologie: string, label: string, description: string | null): LigneTypologie {
  const qui = [{ animateurId: 'a', nom: 'Alice M.' }];
  return {
    typologie,
    label,
    ninja: false,
    maxCreneauxParAnimateur: null,
    description,
    animateursAffectes: qui,
    animateursCompetents: qui,
    competentsJamaisAffectes: [],
    affectesSansCompetence: typologie === 'STRAT' ? qui : [],
    heures: 12.5,
    postes: 4,
    heuresParJour: {},
  };
}

async function monter(filtres: { typologie?: string; filtre?: string } = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: PlanningApi,
        useValue: {
          typologiesReport: vi.fn(async () => ({
            typologies: [
              ligne('AMB', 'Ambiance', null),
              ligne('STRAT', 'Stratégie', '45 jeux à apprendre'),
            ],
            jours: [],
          })),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(PlanningTypologieView);
  fixture.componentRef.setInput('typologie', filtres.typologie ?? '');
  fixture.componentRef.setInput('filtre', filtres.filtre ?? '');
  await fixture.whenStable();
  const racine = fixture.nativeElement as HTMLElement;
  const lignes = () =>
    Array.from(racine.querySelectorAll('tbody th')).map((th) =>
      th.textContent!.replace(/\s+/g, ' ').trim(),
    );
  return { fixture, racine, lignes };
}

describe('PlanningTypologieView', () => {
  it('draws one line per category, its note under its name, the untrained flagged', async () => {
    const { racine, lignes } = await monter();

    expect(lignes()).toEqual(['Ambiance', 'Stratégie 45 jeux à apprendre']);
    expect(racine.querySelectorAll('.planning-typologie-alerte')).toHaveLength(1);
  });

  it('keeps the category and the text the page filters on, the note included', async () => {
    expect((await monter({ typologie: 'AMB' })).lignes()).toEqual(['Ambiance']);
    TestBed.resetTestingModule();
    expect((await monter({ filtre: 'jeux' })).lignes()).toEqual(['Stratégie 45 jeux à apprendre']);
  });

  it('asks the page for « Par stand » narrowed to the category whose seats were clicked', async () => {
    const { fixture, racine } = await monter();
    const demandes: string[] = [];
    fixture.componentInstance.standsDemandes.subscribe((typologie) => demandes.push(typologie));

    racine.querySelectorAll<HTMLButtonElement>('.planning-typologie-lien')[2].click();
    expect(demandes).toEqual(['STRAT']);
  });
});
