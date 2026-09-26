// « Consignes au solveur »: three former screens as tabs of one page. What the
// page decides is which tab `?onglet=` opens, and that « Relancer le calcul »
// is offered in place once a tab wrote something the next solve must respect.

import { Component, output, provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { ActivatedRoute, ParamMap, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RelancerCalcul } from '../../shared/relancer-calcul';
import { AdHocConstraintsPage } from '../ad-hoc-constraints/ad-hoc-constraints-page';
import { ConsignesPage } from '../consignes/consignes-page';
import { VerrouillagesPage } from '../verrouillages/verrouillages-page';
import { readOngletConsignesSolveur } from './consignes-solveur';
import { ConsignesSolveurPage } from './consignes-solveur-page';

@Component({ selector: 'app-ad-hoc-constraints-page', template: 'ajustements' })
class FakeAjustements {
  readonly changed = output<void>();
}

@Component({ selector: 'app-verrouillages-page', template: 'verrouillages' })
class FakeVerrouillages {
  readonly changed = output<void>();
}

@Component({ selector: 'app-consignes-page', template: 'consignes' })
class FakeConsignes {
  readonly changed = output<void>();
}

@Component({ selector: 'app-relancer-calcul', template: 'relancer' })
class FakeRelancer {}

describe('readOngletConsignesSolveur', () => {
  it('reads the three tabs, and opens the first one on anything else', () => {
    expect(readOngletConsignesSolveur('verrouillages')).toBe('verrouillages');
    expect(readOngletConsignesSolveur('consignes')).toBe('consignes');
    expect(readOngletConsignesSolveur('reseau')).toBe('ajustements');
    expect(readOngletConsignesSolveur(null)).toBe('ajustements');
  });
});

describe('ConsignesSolveurPage', () => {
  let fixture: ComponentFixture<ConsignesSolveurPage>;
  let path: string;
  let params: BehaviorSubject<ParamMap>;
  const location = {
    path: () => path,
    replaceState: vi.fn((chemin: string, query = '') => {
      path = query ? `${chemin}?${query}` : chemin;
    }),
  };

  beforeEach(() => {
    location.replaceState.mockClear();
  });

  async function monter(url: string): Promise<HTMLElement> {
    path = url;
    params = new BehaviorSubject<ParamMap>(
      convertToParamMap(Object.fromEntries(new URLSearchParams(url.split('?')[1] ?? ''))),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Location, useValue: location },
        { provide: ActivatedRoute, useValue: { queryParamMap: params } },
      ],
    });
    TestBed.overrideComponent(ConsignesSolveurPage, {
      remove: { imports: [AdHocConstraintsPage, VerrouillagesPage, ConsignesPage, RelancerCalcul] },
      add: { imports: [FakeAjustements, FakeVerrouillages, FakeConsignes, FakeRelancer] },
    });
    fixture = TestBed.createComponent(ConsignesSolveurPage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('says once what the three tabs have in common', async () => {
    const racine = await monter('/consignes-solveur');

    expect(racine.querySelectorAll('h1')).toHaveLength(1);
    expect(racine.textContent).toContain('Ce que le prochain calcul doit respecter.');
    expect(racine.querySelector('app-ad-hoc-constraints-page')).not.toBeNull();
  });

  it('opens the tab the address names', async () => {
    const racine = await monter('/consignes-solveur?onglet=verrouillages&animateur=a1');

    expect(racine.querySelector('app-verrouillages-page')).not.toBeNull();
    expect(racine.querySelector('app-ad-hoc-constraints-page')).toBeNull();
  });

  /** The palette's « Consignes au solveur › Verrouillages », used from this very page. */
  it('follows the address instead of reading it once', async () => {
    const racine = await monter('/consignes-solveur');

    params.next(convertToParamMap({ onglet: 'verrouillages' }));
    await fixture.whenStable();

    expect(racine.querySelector('app-verrouillages-page')).not.toBeNull();
    expect(racine.querySelector('app-ad-hoc-constraints-page')).toBeNull();
  });

  it('offers to relaunch the solve once a tab wrote something, and not before', async () => {
    const racine = await monter('/consignes-solveur?onglet=consignes');
    expect(racine.querySelector('app-relancer-calcul')).toBeNull();

    const consignes = fixture.debugElement.query((element) => element.name === 'app-consignes-page')
      .componentInstance as FakeConsignes;
    consignes.changed.emit();
    await fixture.whenStable();

    expect(racine.querySelector('app-relancer-calcul')).not.toBeNull();
    expect(racine.textContent).toContain('Le prochain calcul en tiendra compte.');
  });

  it('forgets the keys of the tab it leaves', async () => {
    await monter('/consignes-solveur?onglet=verrouillages&animateur=a1');

    (
      fixture.componentInstance as unknown as { changerOnglet: (onglet: string) => void }
    ).changerOnglet('consignes');
    await fixture.whenStable();

    expect(path).toBe('/consignes-solveur?onglet=consignes');
  });
});
