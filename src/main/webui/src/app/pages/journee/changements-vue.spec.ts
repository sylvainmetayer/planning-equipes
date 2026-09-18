// The Changements card over the page's filters: the counters follow what the
// filters kept and say so, and a change of day never shows the previous day's
// figures while the new one loads — a reference toggle does keep them.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JourneesApi } from '../../core/api/journees-api';
import { ChangementSiege, ChangementsJournee, TitulaireSiege } from '../../core/models';
import { ChangementsView } from './changements-vue';

const ALICE: TitulaireSiege = { animateurId: 'alice', nomAffiche: 'Alice Martin' };
const BOB: TitulaireSiege = { animateurId: 'bob', nomAffiche: 'Bob Durand' };
const CAROLE: TitulaireSiege = { animateurId: 'carole', nomAffiche: 'Carole Petit' };

function seat(overrides: Partial<ChangementSiege> & Pick<ChangementSiege, 'standId' | 'type'>) {
  return {
    standNom: overrides.standId,
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    heureDebutAvant: null,
    heureFinAvant: null,
    avant: null,
    apres: null,
    ...overrides,
  };
}

function changements(jour: string): ChangementsJournee {
  return {
    jour,
    reference: 'PUBLICATION',
    referenceDisponible: true,
    referenceLe: '2026-07-20T10:00:00Z',
    nouveaux: 1,
    retires: 0,
    remplaces: 1,
    horairesModifies: 1,
    animateursConcernes: 3,
    parVacation: [
      seat({ standId: 'TIR', type: 'REMPLACE', avant: ALICE, apres: BOB }),
      seat({ standId: 'DIXIT', type: 'NOUVEAU', apres: ALICE }),
      seat({
        standId: 'TIR',
        type: 'HORAIRES',
        heureDebut: '18:00',
        heureFin: '20:00',
        heureDebutAvant: '14:00',
        heureFinAvant: '20:00',
        avant: CAROLE,
        apres: CAROLE,
      }),
    ],
    parAnimateur: [
      {
        animateurId: 'alice',
        nomAffiche: 'Alice Martin',
        changements: [
          { type: 'DEPLACEMENT', libelle: 'samedi 01/08 : Dixit 10h-12h remplace Tir 10h-12h' },
        ],
      },
      {
        animateurId: 'bob',
        nomAffiche: 'Bob Durand',
        changements: [{ type: 'AJOUT', libelle: 'samedi 01/08 : Tir 10h-12h (nouveau)' }],
      },
      {
        animateurId: 'carole',
        nomAffiche: 'Carole Petit',
        changements: [
          { type: 'DEPLACEMENT', libelle: 'samedi 01/08 : Tir 18h-20h remplace Tir 14h-20h' },
        ],
      },
    ],
  };
}

describe('ChangementsView', () => {
  const api = {
    changements: vi.fn<(jour: string, reference: string | null) => Promise<ChangementsJournee>>(),
  };
  let fixture: ComponentFixture<ChangementsView>;

  beforeEach(() => {
    api.changements.mockReset();
    api.changements.mockImplementation(async (jour) => changements(jour));
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: JourneesApi, useValue: api },
      ],
    });
    fixture = TestBed.createComponent(ChangementsView);
    fixture.componentRef.setInput('jour', '2026-08-01');
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function compteurs(): string[] {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('.staffing-stat-value')].map(
      (each) => each.textContent?.trim() ?? '',
    );
  }

  it('counts the whole day when nothing is filtered, and words a seat kept on other hours', async () => {
    await fixture.whenStable();

    expect(compteurs()).toEqual(['1', '0', '1', '1', '3']);
    expect(text()).not.toContain('filtré');
    expect(text()).toContain('14:00 – 20:00 → 18:00 – 20:00');
    expect(text()).toContain('horaires modifiés');
  });

  it('counts what the filters kept, and says the figures are filtered', async () => {
    fixture.componentRef.setInput('stand', 'DIXIT');
    await fixture.whenStable();

    expect(compteurs()).toEqual(['1', '0', '0', '0', '1']);
    expect(text()).toContain('filtré');

    // The stand filter on the people: by the ids the seat lines carry, so
    // « Tir » never catches a person whose sentence names another stand.
    fixture.componentRef.setInput('stand', 'TIR');
    fixture.componentRef.setInput('reading', 'animateurs');
    await fixture.whenStable();
    expect(compteurs()).toEqual(['0', '0', '1', '1', '3']);
    expect(text()).toContain('Carole Petit');
    expect(text()).toContain('Alice Martin');
  });

  it('keeps the figures across a reference toggle, not across a change of day', async () => {
    await fixture.whenStable();
    expect(compteurs()).toHaveLength(5);

    // `whenStable` would wait for the pending read: the screen is looked at
    // while it is still in flight, after a synchronous tick.
    let release: (value: ChangementsJournee) => void = () => undefined;
    api.changements.mockImplementationOnce(() => new Promise((resolve) => (release = resolve)));
    fixture.componentRef.setInput('reference', 'resolution');
    await vi.waitFor(() => expect(api.changements).toHaveBeenCalledTimes(2));
    TestBed.tick();
    expect(compteurs()).toHaveLength(5);
    release(changements('2026-08-01'));
    await fixture.whenStable();

    api.changements.mockImplementationOnce(() => new Promise((resolve) => (release = resolve)));
    fixture.componentRef.setInput('jour', '2026-08-02');
    await vi.waitFor(() => expect(api.changements).toHaveBeenCalledTimes(3));
    TestBed.tick();
    expect(compteurs()).toHaveLength(0);
    release(changements('2026-08-02'));
    await fixture.whenStable();
    expect(compteurs()).toHaveLength(5);
  });
});
