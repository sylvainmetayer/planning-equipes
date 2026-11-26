// The plan by game category at the foot of Besoin: one table, every figure a way somewhere.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { LigneTypologie } from '../../core/models';
import { BesoinTypologie } from './besoin-typologie';

function ligne(typologie: string, label: string, description: string | null): LigneTypologie {
  const qui = [{ animateurId: 'a', nom: 'Alice M.' }];
  return {
    typologie,
    label,
    polyvalent: false,
    maxCreneauxParAnimateur: null,
    description,
    animateursAffectes: qui,
    animateursCompetents: qui,
    competentsJamaisAffectes: typologie === 'AMB' ? [{ animateurId: 'b', nom: 'Bruno D.' }] : [],
    affectesSansCompetence: typologie === 'STRAT' ? qui : [],
    heures: 12.5,
    postes: 4,
    heuresParJour: {},
  };
}

async function monter() {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: PlanningApi,
        useValue: {
          typologiesReport: vi.fn(() =>
            Promise.resolve({
              typologies: [
                ligne('AMB', 'Ambiance', null),
                ligne('STRAT', 'Stratégie', '45 jeux à apprendre'),
              ],
              jours: [],
            }),
          ),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(BesoinTypologie);
  await fixture.whenStable();
  const racine = fixture.nativeElement as HTMLElement;
  const lignes = () =>
    Array.from(racine.querySelectorAll('tbody th')).map((th) =>
      th.textContent!.replace(/\s+/g, ' ').trim(),
    );
  return { fixture, racine, lignes };
}

describe('BesoinTypologie', () => {
  it('draws one line per category, its note under its name, the untrained flagged', async () => {
    const { racine, lignes } = await monter();

    expect(lignes()).toEqual(['Ambiance', 'Stratégie 45 jeux à apprendre']);
    expect(racine.querySelectorAll('.besoin-typologie-alerte')).toHaveLength(1);
  });

  it('names who is vetted but never seated, and who is seated without the skill', async () => {
    const { racine } = await monter();
    const noms = Array.from(racine.querySelectorAll('tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll<HTMLAnchorElement>('.besoin-typologie-noms a')).map((lien) => [
        lien.textContent!.trim(),
        lien.getAttribute('href'),
      ]),
    );

    expect(noms).toEqual([[['Bruno D.', '/animateurs/b']], [['Alice M.', '/animateurs/a']]]);
  });

  it('sends the seats of a category to the Planning by stand narrowed to it', async () => {
    const { racine } = await monter();
    const lien = racine.querySelectorAll<HTMLAnchorElement>('tbody tr')[1].querySelector('td a')!;

    expect(lien.getAttribute('href')).toBe('/journee?axe=stand&typologie=STRAT');
  });
});
