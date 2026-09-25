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
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import {
  Creneau,
  Emplacement,
  PlanningEvenement,
  PosteAffectation,
  Stand,
} from '../../core/models';
import { MarqueurJour, readInstant } from './carte-jour';
import { CarteJourMap } from './carte-jour-map';
import { CarteJourView } from './carte-jour-vue';

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
  readonly presentsMax = input<number>(0);
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

describe('CarteJourView', () => {
  let fixture: ComponentFixture<CarteJourView>;

  /** Renders the view as the Journée page feeds it: the plan, the day and the emplacements as inputs. */
  async function rendre(
    evenement: PlanningEvenement,
    entrees: { jour?: number; t?: number | null; stand?: string; charge?: string } = {},
    emplacements: Emplacement[] = [PLACE],
  ): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
    TestBed.overrideComponent(CarteJourView, {
      remove: { imports: [CarteJourMap] },
      add: { imports: [CarteJourMapStub] },
    });
    fixture = TestBed.createComponent(CarteJourView);
    fixture.componentRef.setInput('planning', evenement);
    fixture.componentRef.setInput('emplacements', emplacements);
    for (const [cle, valeur] of Object.entries(entrees)) {
      fixture.componentRef.setInput(cle, valeur);
    }
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

  it('shows the day and the instant the page hands it', async () => {
    await rendre(planningDeuxJours(), { jour: 2, t: 900 });

    expect(heure()).toBe('15:00');
    expect(compteurs()).toContain('1 stand(s) ouvert(s) sur 1');
  });

  it('falls back on the defaults rather than failing on an unusable link', async () => {
    await rendre(planningDeuxJours(), { jour: 99, t: readInstant('midi') });

    expect(heure()).toBe('10:00');
    expect(readInstant('')).toBeNull();
    expect(readInstant('-5')).toBeNull();
    expect(readInstant('900')).toBe(900);
  });

  it('rings the place of the stand the page filters on, until a click picks another', async () => {
    await rendre(planningDeuxJours(), { stand: 'Tir' });

    const place = racine().querySelector('.carte-jour-emplacement.selection');
    expect(place?.textContent).toContain('Place du Drapeau');
  });

  it('clamps an instant that falls outside the chosen day', async () => {
    await rendre(planningDeuxJours(), { t: 1380 });

    // Day 1 runs 10:00-16:00; 23:00 lands on its last minute, not off the scale.
    expect(heure()).toBe('16:00');
  });

  it('resets the cursor and the chosen place in one action', async () => {
    await rendre(planningDeuxJours());
    await curseur(15 * 60);
    expect(heure()).toBe('15:00');

    // The button lives on the Journée page, which calls this on every rendering.
    fixture.componentInstance.reinitialiser();
    await fixture.whenStable();

    expect(heure()).toBe('10:00');
  });

  // The effect on the day input is optional: dropping it compiles and leaves
  // every other test green, while a day change would keep the clock running
  // on yesterday's minute. Pinned here.
  it('stops the replay and returns to the opening hour when the day changes', async () => {
    await rendre(planningDeuxJours());
    await curseur(15 * 60);
    const lecture = racine().querySelector('[data-test="carte-jour-lecture"]') as HTMLElement;
    lecture.click();
    await fixture.whenStable();
    expect(lecture.getAttribute('aria-pressed')).toBe('true');

    fixture.componentRef.setInput('jour', 2);
    await fixture.whenStable();

    expect(lecture.getAttribute('aria-pressed')).toBe('false');
    expect(heure()).toBe('14:00');
  });

  it('lists a stand with no located emplacement instead of dropping it silently', async () => {
    await rendre(planningDeuxJours());

    const nonSitues = racine().querySelector('[data-test="carte-jour-non-situes"]')!;
    expect(nonSitues.textContent).toContain('Dixit');
    expect(nonSitues.textContent).toContain('rattaché à aucun emplacement');
  });

  function grille(): HTMLTableElement {
    return racine().querySelector('[data-test="charge-grille"]') as HTMLTableElement;
  }

  function cellule(ligne: string, colonne: number): HTMLElement {
    const rangee = Array.from(grille().querySelectorAll('tbody tr')).find((tr) =>
      tr.querySelector('th')!.textContent!.includes(ligne),
    )!;
    return rangee.querySelectorAll('td')[colonne] as HTMLElement;
  }

  it('lays the day out as places by spans, the stands tied to nothing in a row of their own', async () => {
    await rendre(planningDeuxJours());

    const entetes = Array.from(grille().querySelectorAll('thead th')).map((th) =>
      th.textContent!.trim(),
    );
    expect(entetes).toEqual(['Emplacement', '10:00 – 12:00', '14:00 – 16:00']);
    expect(grille().querySelector('caption')!.textContent).toContain('Charge par emplacement');
    expect(cellule('Place du Drapeau', 0).textContent!.trim()).toBe('1/1');
    expect(cellule('Sans emplacement', 1).textContent!.trim()).toBe('0/1');
    expect(cellule('Total sur le site', 1).getAttribute('aria-label')).toContain(
      '0 personne(s) présente(s) sur 1 place(s)',
    );
  });

  it('moves the map cursor to the span of a cell, by click or by Enter', async () => {
    await rendre(planningDeuxJours());

    cellule('Sans emplacement', 1).click();
    await fixture.whenStable();
    expect(heure()).toBe('14:00');
    expect(fixture.componentInstance.minutesSelectionnees()).toBe(14 * 60);

    cellule('Place du Drapeau', 0).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    await fixture.whenStable();
    expect(heure()).toBe('10:00');
  });

  it('walks the grid with the arrows, one Tab stop for the whole table', async () => {
    await rendre(planningDeuxJours());

    const focusables = grille().querySelectorAll('td[tabindex="0"]');
    expect(focusables.length).toBe(1);
    const premiere = focusables[0] as HTMLElement;
    premiere.focus();
    premiere.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await fixture.whenStable();
    expect(document.activeElement?.getAttribute('data-colonne')).toBe('1');
  });

  it('reads the whole event one column per day, and opens a day at its peak', async () => {
    const demandes: number[] = [];
    await rendre(planningDeuxJours(), { charge: 'evenement' });
    fixture.componentInstance.jourDemande.subscribe((jour) => demandes.push(jour));

    const entetes = Array.from(grille().querySelectorAll('thead th')).map((th) =>
      th.textContent!.trim(),
    );
    expect(entetes).toEqual(['Emplacement', 'Jour 1 — 2026-08-01', 'Jour 2 — 2026-08-02']);
    expect(cellule('Place du Drapeau', 1).textContent!.trim()).toBe('1/1');

    cellule('Place du Drapeau', 1).click();
    await fixture.whenStable();
    expect(demandes).toEqual([2]);

    // The page answers by switching day: the cursor lands on the peak, not the opening.
    fixture.componentRef.setInput('jour', 2);
    await fixture.whenStable();
    expect(heure()).toBe('14:00');
    expect(fixture.componentInstance.minutesSelectionnees()).toBe(14 * 60);
  });

  it('says what to do rather than showing an empty map when no plan is persisted', async () => {
    await rendre({ animateurs: [], postes: [], score: null });

    expect(racine().textContent).toContain('Aucun planning enregistré');
    expect(racine().querySelector('[data-test="carte-jour-curseur"]')).toBeNull();
  });
});
