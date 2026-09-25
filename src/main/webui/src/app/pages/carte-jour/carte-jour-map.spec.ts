// What only the Leaflet half can answer: the attributes a marker really ends
// up carrying, and what happens to the deferred re-measure when the route is
// left in the same frame as it was entered.
//
// jsdom has no layout, so nothing here asserts anything geometric — it asserts
// the DOM and the lifecycle, which is where the two defects lived.

import {
  ChangeDetectionStrategy,
  Component,
  provideZonelessChangeDetection,
  signal,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import * as L from 'leaflet';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MarqueurJour, StandInstant } from './carte-jour';
import { CarteJourMap } from './carte-jour-map';

@Component({
  selector: 'app-carte-jour-map-host',
  imports: [CarteJourMap],
  template: `<app-carte-jour-map
    [marqueurs]="marqueurs()"
    [presentsMax]="presentsMax()"
    [cadrage]="'1'"
  />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  readonly marqueurs = signal<MarqueurJour[]>([]);
  readonly presentsMax = signal(0);
}

function stand(): StandInstant {
  return {
    standId: 'S1',
    nom: 'Stand',
    etat: 'ferme',
    sieges: 0,
    pourvus: 0,
    horaire: '',
    creneauId: null,
    emplacementNom: 'Place du Drapeau',
    resume: 'Stand — fermé à cette heure-là',
  };
}

function marqueur(overrides: Partial<MarqueurJour> = {}): MarqueurJour {
  return {
    emplacementId: 'PLACE',
    nom: 'Place du Drapeau',
    latitude: 46.65,
    longitude: -0.25,
    etat: 'ferme',
    stands: [],
    ouverts: 0,
    sieges: 0,
    pourvus: 0,
    resume: 'Place du Drapeau : aucun stand ouvert à cette heure-là, sur 3 rattaché(s)',
    ...overrides,
  };
}

function mount(marqueurs: MarqueurJour[]) {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.marqueurs.set(marqueurs);
  fixture.detectChanges();
  fixture.detectChanges();
  return fixture;
}

describe('CarteJourMap', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  it('writes the people present on the badge, zero included, and the seats beside it', () => {
    // Three stands attached, none open: the badge must read « 0 », not « 3 ».
    const fixture = mount([
      marqueur({ etat: 'ferme', ouverts: 0, stands: [stand(), stand(), stand()] }),
      marqueur({
        emplacementId: 'MAIRIE',
        nom: 'Mairie',
        etat: 'partiel',
        ouverts: 2,
        pourvus: 5,
        sieges: 7,
      }),
    ]);

    const text = (selecteur: string) =>
      Array.from(fixture.nativeElement.querySelectorAll(selecteur) as NodeListOf<HTMLElement>).map(
        (element) => element.textContent,
      );
    expect(text('.carte-jour-pastille')).toEqual(expect.arrayContaining(['0', '5']));
    expect(text('.carte-jour-pastille-sieges')).toEqual(['/7']);

    fixture.destroy();
  });

  it('grows the badge with the people present, against the fullest place of the day', () => {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.marqueurs.set([
      marqueur({ etat: 'pourvu', pourvus: 20, sieges: 20 }),
      marqueur({ emplacementId: 'MAIRIE', nom: 'Mairie', etat: 'pourvu', pourvus: 1, sieges: 1 }),
    ]);
    fixture.componentInstance.presentsMax.set(20);
    fixture.detectChanges();
    fixture.detectChanges();

    const tailles = Array.from(
      fixture.nativeElement.querySelectorAll('.carte-jour-pastille') as NodeListOf<HTMLElement>,
    ).map((pastille) => parseInt(pastille.style.width, 10));
    expect(Math.max(...tailles)).toBe(56);
    expect(Math.min(...tailles)).toBeLessThan(56);

    fixture.destroy();
  });

  it('names a marker without a native title, so nothing doubles the Leaflet tooltip', () => {
    const fixture = mount([marqueur()]);

    const icone = fixture.nativeElement.querySelector('.leaflet-marker-icon') as HTMLElement;
    expect(icone).not.toBeNull();
    expect(icone.getAttribute('aria-label')).toBe('Place du Drapeau');
    expect(icone.hasAttribute('title')).toBe(false);

    fixture.destroy();
  });

  it('never re-measures a map it has already destroyed', () => {
    // Leaving the route in the same frame as entering it: `Map.remove()` drops
    // `_mapPane` but leaves `_loaded` true, so a late `invalidateSize()` throws.
    const frames: FrameRequestCallback[] = [];
    vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation((rappel) =>
      frames.push(rappel),
    );
    const annulation = vi
      .spyOn(globalThis, 'cancelAnimationFrame')
      .mockImplementation(() => undefined);
    const mesure = vi.spyOn(L.Map.prototype, 'invalidateSize');

    const fixture = mount([marqueur()]);
    expect(frames.length).toBeGreaterThan(0);
    fixture.destroy();

    expect(annulation).toHaveBeenCalled();
    expect(() => frames.forEach((rappel) => rappel(0))).not.toThrow();
    expect(mesure).not.toHaveBeenCalled();

    vi.restoreAllMocks();
  });
});
