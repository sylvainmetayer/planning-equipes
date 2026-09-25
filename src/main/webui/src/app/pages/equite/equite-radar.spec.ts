// The radar component over a small report: the polygons it draws, the values
// at the end of its axes, the marks on them, and its accessible name. The
// geometry itself is tested in radar.spec.ts.

import { LOCALE_ID, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LigneEquite, RapportEquite } from '../../core/models';
import { EquiteRadar } from './equite-radar';

function row(animateurId: string, partial: Partial<LigneEquite> = {}): LigneEquite {
  return {
    animateurId,
    nom: animateurId,
    heuresTotal: 0,
    heuresParSemaine: {},
    heuresSoiree: 0,
    heuresWeekEnd: 0,
    heuresJourFerie: 0,
    postes: 0,
    postesPenibles: 0,
    standsDistincts: 0,
    typologiesDistinctes: 0,
    emplacementsDistinctsParJourMax: 0,
    tauxSouhaits: 0,
    tauxAppreciation: 0,
    joursTravailles: 0,
    joursRepos: 0,
    plusLongueSerie: 0,
    ...partial,
  };
}

const ALICE = row('Alice', {
  heuresTotal: 42,
  heuresSoiree: 9,
  postesPenibles: 3,
  tauxSouhaits: 0.5,
});
const BRUNO = row('Bruno', {
  heuresTotal: 20,
  heuresSoiree: 1,
  postesPenibles: 0,
  tauxSouhaits: 1,
});

const REPORT: RapportEquite = {
  heureDebutSoiree: '19:00:00',
  semaines: [],
  lignes: [ALICE, BRUNO],
  syntheses: {
    heuresTotal: { min: 20, mediane: 31, max: 42, ecartType: 0 },
    heuresSoiree: { min: 1, mediane: 5, max: 9, ecartType: 0 },
    heuresWeekEnd: { min: 0, mediane: 0, max: 0, ecartType: 0 },
    postesPenibles: { min: 0, mediane: 1.5, max: 3, ecartType: 0 },
    tauxSouhaits: { min: 0.5, mediane: 0.75, max: 1, ecartType: 0 },
  },
  colonnesSolveur: [{ colonne: 'postesPenibles', contrainte: 'equitePenibilite', active: true }],
};

function render(
  inputs: { second?: LigneEquite | null; optionalAxes?: string[]; report?: RapportEquite } = {},
) {
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), { provide: LOCALE_ID, useValue: 'en-US' }],
  });
  const fixture = TestBed.createComponent(EquiteRadar);
  fixture.componentRef.setInput('report', inputs.report ?? REPORT);
  fixture.componentRef.setInput('row', ALICE);
  fixture.componentRef.setInput('second', inputs.second ?? null);
  fixture.componentRef.setInput('optionalAxes', inputs.optionalAxes ?? []);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

function labels(element: HTMLElement): string[] {
  return Array.from(element.querySelectorAll('.equite-radar-label')).map((label) =>
    Array.from(label.children)
      .map((line) => line.textContent!.replace(/\s+/g, ' ').trim())
      .join(' '),
  );
}

describe('EquiteRadar', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('draws the person, the median and the band over five axes', () => {
    const element = render();
    expect(
      element.querySelector('.equite-radar-person')!.getAttribute('points')!.split(' '),
    ).toHaveLength(5);
    expect(element.querySelector('.equite-radar-median')).not.toBeNull();
    expect(element.querySelector('.equite-radar-band')).not.toBeNull();
    expect(element.querySelector('.equite-radar-second')).toBeNull();
    expect(labels(element)).toHaveLength(5);
  });

  it('writes the values as the fiche table does, with the median', () => {
    const [hours, , weekEnd, , wishes] = labels(render());
    expect(hours).toBe('Heures 42.0 h méd. 31.0 h');
    expect(weekEnd).toBe('Week-end 0.0 h méd. 0.0 h aucune dispersion');
    expect(wishes).toContain('↑ mieux');
    expect(wishes).toContain('50%');
  });

  it('marks the axis a switched-on solver rule measures', () => {
    const element = render();
    const penible = element.querySelectorAll('.equite-radar-label')[3];
    expect(penible.querySelector('.equite-solveur-icon')).not.toBeNull();
    expect(element.querySelectorAll('.equite-radar-label .equite-solveur-icon')).toHaveLength(1);
  });

  it('adds the optional axes and a second person', () => {
    const element = render({ second: BRUNO, optionalAxes: ['plusLongueSerie'] });
    expect(labels(element)).toHaveLength(6);
    expect(element.querySelector('.equite-radar-second')).not.toBeNull();
    expect(labels(element)[0]).toContain('Bruno : 20.0 h');
    expect(element.querySelector('svg')!.getAttribute('aria-label')).toContain('et à Bruno');
  });

  it('keys its legend with the classes the stylesheet colours', () => {
    const element = render({ second: BRUNO });
    const swatches = Array.from(element.querySelectorAll('.equite-radar-swatch')).map((swatch) =>
      swatch.className.replace('equite-radar-swatch ', ''),
    );
    expect(swatches).toEqual([
      'equite-radar-swatch-person',
      'equite-radar-swatch-second',
      'equite-radar-swatch-median',
      'equite-radar-swatch-band',
    ]);
  });

  it('is an image named after the person, and says when there is nobody to compare with', () => {
    const element = render({ report: { ...REPORT, lignes: [ALICE] } });
    const svg = element.querySelector('svg')!;
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toContain('Radar de Alice');
    expect(element.textContent).toContain('rien à comparer');
  });
});
