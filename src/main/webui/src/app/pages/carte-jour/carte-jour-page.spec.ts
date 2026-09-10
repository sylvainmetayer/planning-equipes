// What `carte-jour.spec.ts` cannot see: how the day and the instant are read
// back from the URL, what moving the cursor changes on screen, and what the
// page says when there is no plan to replay at all.
//
// The Leaflet child is replaced by a stub: `jsdom` has no layout, so a real
// map would measure an empty container and prove nothing — and the map is
// deliberately not the accessible surface here, the list below it is.

import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  provideZonelessChangeDetection,
} from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import {
  Creneau,
  Emplacement,
  PlanningEvenement,
  PosteAffectation,
  Stand,
} from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { MarqueurJour } from './carte-jour';
import { CarteJourMap } from './carte-jour-map';
import { CarteJourPage } from './carte-jour-page';

@Component({
  selector: 'app-carte-jour-map',
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class CarteJourMapStub {
  readonly marqueurs = input.required<MarqueurJour[]>();
  readonly selection = input<string | null>(null);
  readonly cadrage = input<string>('');
  readonly ariaLabel = input<string>('');
  readonly marqueurChoisi = output<string>();
}

const PLACE: Emplacement = {
  id: 'PLACE',
  nom: 'Place du Drapeau',
  latitude: 46.65,
  longitude: -0.25,
};

function stand(id: string, emplacement: Emplacement | null): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

const ALICE = {
  id: 'A',
  prenom: 'Alice',
  nom: '',
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: [],
};

/**
 * Day 1: the located stand opens 10:00-12:00 with its seat filled, an
 * unlocated one opens 14:00-16:00 with nobody. Day 2 holds one afternoon seat.
 */
function planningDeuxJours(): PlanningEvenement {
  return {
    animateurs: [ALICE],
    postes: [
      poste({
        id: 'p1',
        creneau: creneau({ id: 1 }),
        stand: stand('Tir', PLACE),
        animateur: ALICE,
      }),
      poste({
        id: 'p2',
        creneau: creneau({ id: 2, heureDebut: '14:00', heureFin: '16:00' }),
        stand: stand('Dixit', null),
      }),
      poste({
        id: 'p3',
        creneau: creneau({
          id: 3,
          jour: 2,
          date: '2026-08-02',
          heureDebut: '14:00',
          heureFin: '16:00',
        }),
        stand: stand('Tir', PLACE),
        animateur: ALICE,
      }),
    ],
    score: null,
  };
}

describe('CarteJourPage', () => {
  let fixture: ComponentFixture<CarteJourPage>;

  async function rendre(
    evenement: PlanningEvenement,
    queryParams: Record<string, string> = {},
    emplacements: Emplacement[] = [PLACE],
  ): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
        { provide: ApiService, useValue: { get: vi.fn(async () => emplacements) } },
        {
          provide: PlanningStateService,
          useValue: { loadForDisplay: vi.fn(async () => evenement) },
        },
      ],
    });
    TestBed.overrideComponent(CarteJourPage, {
      remove: { imports: [CarteJourMap] },
      add: { imports: [CarteJourMapStub] },
    });
    fixture = TestBed.createComponent(CarteJourPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function heure(): string {
    return racine().querySelector('[data-test="carte-jour-heure"]')!.textContent!.trim();
  }

  function compteurs(): string {
    return racine().querySelector('[data-test="carte-jour-compteurs"]')!.textContent!.trim();
  }

  function etats(): string[] {
    return Array.from(racine().querySelectorAll('.carte-jour-liste .carte-jour-pastille')).map(
      (each) => each.className,
    );
  }

  async function curseur(minutes: number): Promise<void> {
    const entree = racine().querySelector('[data-test="carte-jour-curseur"]') as HTMLInputElement;
    entree.value = String(minutes);
    entree.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
  }

  it('opens on the first day, at its opening hour', async () => {
    await rendre(planningDeuxJours());

    expect(heure()).toBe('10:00');
    expect(compteurs()).toContain('1 stand(s) ouvert(s) sur 2');
  });

  it('changes the state of the stands as the cursor moves', async () => {
    await rendre(planningDeuxJours());
    expect(etats().join(' ')).toContain('etat-pourvu');

    // 13:00: the morning stand has closed and the afternoon one has not opened.
    await curseur(13 * 60);
    expect(heure()).toBe('13:00');
    expect(compteurs()).toContain('0 stand(s) ouvert(s) sur 2');
    expect(etats().join(' ')).toContain('etat-ferme');

    // 15:00: the unlocated stand is open with nobody on it.
    await curseur(15 * 60);
    expect(compteurs()).toContain('1 stand(s) ouvert(s) sur 2');
    expect(racine().querySelector('[data-test="carte-jour-non-situes"]')!.textContent).toContain(
      'aucune pourvue',
    );
  });

  it('restores the day and the instant named by the URL', async () => {
    await rendre(planningDeuxJours(), { jour: '2', t: '900' });

    expect(heure()).toBe('15:00');
    expect(compteurs()).toContain('1 stand(s) ouvert(s) sur 1');
  });

  it('falls back on the defaults rather than failing on an unusable URL', async () => {
    await rendre(planningDeuxJours(), { jour: '99', t: 'midi' });

    expect(heure()).toBe('10:00');
  });

  it('clamps an instant that falls outside the chosen day', async () => {
    await rendre(planningDeuxJours(), { t: '1380' });

    // Day 1 runs 10:00-16:00; 23:00 lands on its last minute, not off the scale.
    expect(heure()).toBe('16:00');
  });

  it('resets the cursor and the chosen place in one action', async () => {
    await rendre(planningDeuxJours());
    await curseur(15 * 60);
    expect(heure()).toBe('15:00');

    const reinitialiser = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Réinitialiser la vue'),
    ) as HTMLElement;
    reinitialiser.click();
    await fixture.whenStable();

    expect(heure()).toBe('10:00');
  });

  it('lists a stand with no located emplacement instead of dropping it silently', async () => {
    await rendre(planningDeuxJours());

    const nonSitues = racine().querySelector('[data-test="carte-jour-non-situes"]')!;
    expect(nonSitues.textContent).toContain('Dixit');
    expect(nonSitues.textContent).toContain('rattaché à aucun emplacement');
  });

  it('says what to do rather than showing an empty map when no plan is persisted', async () => {
    await rendre({ animateurs: [], postes: [], score: null });

    expect(racine().textContent).toContain('Aucun planning enregistré');
    expect(racine().querySelector('[data-test="carte-jour-curseur"]')).toBeNull();
  });
});
