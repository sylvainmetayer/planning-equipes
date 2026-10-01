// The comparison of two editions over a typed fake of its API: the summary
// sentence, the banner of a duplication without the people, the name-matched
// flag, the volumes side by side, « ouvrir dans l'édition » switching the
// browser's edition, and the CSV.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../../core/api/editions-api';
import { EditionStore } from '../../core/edition.store';
import { DeltaFamilyCount, Edition, EditionDelta } from '../../core/models';
import { seedStore } from '../../core/testing/seed-store';
import { ComparerEditionsPage } from './comparer-editions-page';

function edition(id: string, nom: string): Edition {
  return { id, nom, active: false, creeLe: null };
}

function family(
  family: DeltaFamilyCount['family'],
  counts: Partial<DeltaFamilyCount> = {},
): DeltaFamilyCount {
  return { family, added: 0, removed: 0, modified: 0, matchedByName: 0, ...counts };
}

function delta(overrides: Partial<EditionDelta> = {}): EditionDelta {
  return {
    reference: { id: 'E1', nom: 'Année 2025' },
    target: { id: 'E2', nom: 'Année 2026' },
    summary: {
      families: [
        family('STAND', { added: 3, modified: 1, matchedByName: 2 }),
        family('ANIMATEUR', { removed: 12 }),
        family('TYPOLOGIE'),
      ],
      referenceDays: 4,
      targetDays: 4,
      seatDifference: 40,
      hoursToFillDifference: 410.4,
    },
    noAnimateurMatched: false,
    typologies: [],
    emplacements: [],
    stands: [
      {
        change: 'MODIFIED',
        matching: 'NOM',
        referenceId: 'S1',
        targetId: 'S1',
        code: null,
        label: 'Cirque',
        fields: ['effectifMax'],
      },
      {
        change: 'REMOVED',
        matching: null,
        referenceId: 'S2',
        targetId: null,
        code: 'ECH',
        label: 'Échecs',
        fields: [],
      },
    ],
    animateurs: [
      {
        change: 'REMOVED',
        matching: null,
        referenceId: 'A151',
        targetId: null,
        code: null,
        label: 'Bob Durand',
        fields: [],
      },
    ],
    journeesTypes: [],
    creneaux: [],
    parametres: [
      {
        group: 'CONSTRAINT_WEIGHT',
        key: 'equiteHeures',
        label: 'Équité des heures',
        referenceValue: '5',
        targetValue: '25',
      },
    ],
    ajustements: [],
    referenceVolumes: {
      animateurCount: 150,
      posteCount: 400,
      hoursToFill: 1600,
      hoursAvailable: 4000,
      fillRatio: 0.4,
    },
    targetVolumes: {
      animateurCount: 138,
      posteCount: 440,
      hoursToFill: 2010.4,
      hoursAvailable: 3700,
      fillRatio: 0.5433,
    },
    ...overrides,
  };
}

function fakeApi() {
  return {
    delta: vi.fn<EditionsApi['delta']>(() => Promise.resolve(delta())),
    exportDeltaCsv: vi.fn<EditionsApi['exportDeltaCsv']>(() =>
      Promise.resolve('delta-E1-E2.csv téléchargé'),
    ),
  };
}

describe('ComparerEditionsPage', () => {
  let api: ReturnType<typeof fakeApi>;
  let store: EditionStore;

  beforeEach(() => {
    api = fakeApi();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: EditionsApi, useValue: api },
      ],
    });
    store = TestBed.inject(EditionStore);
    seedStore(store, 'editions', [edition('E1', 'Année 2025'), edition('E2', 'Année 2026')]);
    seedStore(store, 'courant', edition('E2', 'Année 2026'));
  });

  async function render(): Promise<ComponentFixture<ComparerEditionsPage>> {
    const fixture = TestBed.createComponent(ComparerEditionsPage);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<ComparerEditionsPage>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('compares the first other edition with the one this tab works in, and keeps the pair in the URL', async () => {
    const fixture = await render();

    expect(api.delta).toHaveBeenCalledWith('E1', 'E2');
    expect(TestBed.inject(Location).path()).toContain('a=E1');
    expect(TestBed.inject(Location).path()).toContain('b=E2');
    expect(text(fixture)).toContain('De Année 2025 à Année 2026');
  });

  it('reads the pair from the URL', async () => {
    TestBed.inject(Location).replaceState('/editions/comparer?a=E2&b=E1');

    await render();

    expect(api.delta).toHaveBeenCalledWith('E2', 'E1');
  });

  it('sums the delta up in one sentence', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain(
      '+3 stands, −12 animateurs, +410 heures à pourvoir, 1 fiches modifiées, 1 réglages différents',
    );
  });

  it('says nothing differs on an edition compared with itself', async () => {
    api.delta.mockResolvedValue(
      delta({
        summary: {
          families: [family('STAND')],
          referenceDays: 4,
          targetDays: 4,
          seatDifference: 0,
          hoursToFillDifference: 0,
        },
        stands: [],
        animateurs: [],
        parametres: [],
      }),
    );

    const fixture = await render();

    expect(text(fixture)).toContain('Aucune différence de référentiel entre ces deux éditions.');
  });

  it('flags a match resting on a name, and names the animateurs on this screen', async () => {
    const fixture = await render();
    const racine = fixture.nativeElement as HTMLElement;

    expect(text(fixture)).toContain('Stands : 2 rapprochés par leur nom');
    expect(racine.querySelector('.delta-par-nom')?.textContent).toContain('par nom, faute de code');
    expect(text(fixture)).toContain('Bob Durand');
    expect(text(fixture)).toContain('Parti');
    expect(text(fixture)).toContain('effectif maximum');
    expect(text(fixture)).toContain('Équité des heures');
  });

  it('does not flag a day template matched by its name, its only key', async () => {
    api.delta.mockResolvedValue(
      delta({
        stands: [],
        journeesTypes: [
          {
            change: 'MODIFIED',
            matching: 'NOM',
            referenceId: '1',
            targetId: '7',
            code: null,
            label: 'Jour normal',
            fields: ['vacations'],
          },
        ],
      }),
    );

    const fixture = await render();
    const racine = fixture.nativeElement as HTMLElement;

    expect(text(fixture)).toContain('Jour normal');
    expect(text(fixture)).toContain('par nom');
    expect(text(fixture)).not.toContain('par nom, faute de code');
    expect(racine.querySelector('.delta-par-nom')).toBeNull();
  });

  it('puts the volumes side by side with the server fill ratio', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('Taux de remplissage');
    expect(text(fixture)).toContain('40 %');
    expect(text(fixture)).toContain('54 %');
    expect(text(fixture)).toContain('+14 points');
  });

  it('says so once when the target was duplicated without its people, the list folded', async () => {
    api.delta.mockResolvedValue(delta({ noAnimateurMatched: true }));

    const fixture = await render();
    const racine = fixture.nativeElement as HTMLElement;

    expect(text(fixture)).toContain('dupliquée sans les personnes');
    const panneaux = Array.from(racine.querySelectorAll<HTMLDetailsElement>('details'));
    const animateurs = panneaux.find((panneau) => panneau.textContent?.includes('Bob Durand'));
    expect(animateurs?.open).toBe(false);
  });

  it('opens a fiche in the edition holding it, switching the edition and saying so', async () => {
    const openIn = vi.spyOn(store, 'openIn').mockImplementation(() => undefined);
    const fixture = await render();
    const boutons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        'button.delta-ouvrir',
      ),
    );

    const towardA = boutons.find((bouton) =>
      bouton.textContent?.includes('Ouvrir dans Année 2025'),
    );
    expect(towardA?.textContent).toContain("change d'édition");
    towardA?.click();

    expect(openIn).toHaveBeenCalledWith('E1', '/stands/S2');
  });

  it('opens a fiche of the edition this tab works in without saying it switches', async () => {
    const openIn = vi.spyOn(store, 'openIn').mockImplementation(() => undefined);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = await render();
    const boutons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        'button.delta-ouvrir',
      ),
    );

    const towardB = boutons.find((bouton) =>
      bouton.textContent?.includes('Ouvrir dans Année 2026'),
    );
    expect(towardB?.textContent).not.toContain("change d'édition");
    expect(towardB?.getAttribute('title')).toBeNull();
    towardB?.click();

    expect(openIn).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/stands/S1');
  });

  it('downloads the CSV through the API and says so', async () => {
    const fixture = await render();
    const bouton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ).find((candidat) => candidat.textContent?.includes('Exporter en CSV'));

    bouton?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.exportDeltaCsv).toHaveBeenCalledWith('E1', 'E2');
    expect(text(fixture)).toContain('delta-E1-E2.csv téléchargé');
  });
});
