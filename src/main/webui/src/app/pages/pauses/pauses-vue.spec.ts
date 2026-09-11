// What `pauses.spec.ts` cannot see: that the report reaches the screen grouped
// by stand, that the view follows the day the Journée page hands it, that the
// two empty states and the missing-declaration warning show up.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { RapportPauses } from '../../core/models';
import { PausesView } from './pauses-vue';

function rapport(overrides: Partial<RapportPauses> = {}): RapportPauses {
  return {
    pauseSurPoste: true,
    journeesAnalysees: 3,
    pausesDues: 2,
    relaisManquants: 1,
    coupuresRepasDues: 0,
    coupuresRepasManquantes: 0,
    message: '2 pauses à prendre sur le poste, dont 1 sans relais possible sur le stand.',
    journees: [
      {
        animateurId: 'alice',
        nomComplet: 'Alice Martin',
        mineur: false,
        date: '2026-07-10',
        jour: 3,
        sequences: [
          {
            debut: '13:00:00',
            fin: '20:00:00',
            minutes: 420,
            pausesDues: [
              {
                debut: '18:40:00',
                fin: '19:00:00',
                simultanee: false,
                heureLimite: '19:00:00',
                dureeMinutes: 20,
                standId: 'JEUX',
                standNom: 'Village des jeux',
                relais: [{ animateurId: 'bob', nomComplet: 'Bob Durand' }],
                relaisDisponible: true,
              },
            ],
          },
        ],
        pausesPlanifiees: [{ debut: '12:00:00', fin: '13:00:00', minutes: 60 }],
        coupuresRepas: [],
      },
      {
        animateurId: 'carol',
        nomComplet: 'Carol Petit',
        mineur: true,
        date: '2026-07-11',
        jour: 4,
        sequences: [
          {
            debut: '14:00:00',
            fin: '19:30:00',
            minutes: 330,
            pausesDues: [
              {
                debut: '18:30:00',
                fin: '19:00:00',
                simultanee: true,
                heureLimite: '18:30:00',
                dureeMinutes: 30,
                standId: 'REF',
                standNom: 'Référencement',
                relais: [],
                relaisDisponible: false,
              },
            ],
          },
        ],
        pausesPlanifiees: [],
        coupuresRepas: [],
      },
    ],
    ...overrides,
  };
}

describe('PausesView', () => {
  /** Renders the view as the Journée page feeds it: the report, the day and the filters as inputs. */
  async function mount(
    data: RapportPauses | null,
    entrees: { date?: string; recherche?: string; stand?: string; animateur?: string } = {},
  ): Promise<ComponentFixture<PausesView>> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(PausesView);
    fixture.componentRef.setInput('rapport', data);
    for (const [cle, valeur] of Object.entries(entrees)) {
      fixture.componentRef.setInput(cle, valeur);
    }
    await fixture.whenStable();
    return fixture;
  }

  function text(fixture: ComponentFixture<PausesView>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function titresStands(fixture: ComponentFixture<PausesView>): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.pauses-stand-titre'),
    ).map((titre) => titre.textContent!.replace(/\s+/g, ' ').trim());
  }

  it('shows the first day grouped by stand, with the deadline and the relay', async () => {
    const fixture = await mount(rapport());

    expect(titresStands(fixture)[0]).toContain('Village des jeux');
    const contenu = text(fixture);
    expect(contenu).toContain('Alice Martin');
    expect(contenu).toContain('13:00–20:00');
    expect(contenu).toContain('18:40–19:00');
    expect(contenu).toContain('20 min');
    expect(contenu).toContain('Bob Durand');
    // The scheduled gap of the day is listed apart.
    expect(contenu).toContain('Pauses déjà planifiées');
    expect(contenu).toContain('12:00–13:00');
    // The other day stays out of sight.
    expect(contenu).not.toContain('Carol Petit');
  });

  it('follows the day the page hands it, and flags the missing relay and the minor', async () => {
    const fixture = await mount(rapport());

    fixture.componentRef.setInput('date', '2026-07-11');
    await fixture.whenStable();

    const contenu = text(fixture);
    expect(contenu).toContain('Carol Petit');
    expect(contenu).toContain('mineur');
    expect(contenu).toContain('30 min');
    expect(contenu).toContain("Personne d'autre sur le stand");
    expect(contenu).toContain("en même temps qu'une autre pause");
    expect(titresStands(fixture)[0]).toContain('1 sans relais');
  });

  it('falls back on the first day for a day the report does not know', async () => {
    const fixture = await mount(rapport(), { date: '2030-01-01' });
    expect(text(fixture)).toContain('Alice Martin');
  });

  it('keeps only what the shared filters name: a stand, a person, a text', async () => {
    expect(text(await mount(rapport(), { stand: 'REF' }))).not.toContain('Village des jeux');
    expect(text(await mount(rapport(), { animateur: 'bob' }))).not.toContain('Alice Martin');
    expect(text(await mount(rapport(), { recherche: 'alice' }))).toContain('Alice Martin');
  });

  it('warns when the on-post break is not declared, with a link to the legal parameters', async () => {
    const fixture = await mount(rapport({ pauseSurPoste: false }));

    const alerte = (fixture.nativeElement as HTMLElement).querySelector('.pauses-alerte');
    expect(alerte?.textContent).toContain("n'est pas déclarée");
    expect(alerte?.querySelector('a')?.getAttribute('href')).toBe('/parametres');
  });

  it('says so when nothing is persisted, and when nothing is to organise', async () => {
    expect(
      text(await mount(rapport({ journeesAnalysees: 0, journees: [], pausesDues: 0 }))),
    ).toContain('Aucun planning persisté');

    const rien = await mount(
      rapport({
        journees: [],
        pausesDues: 0,
        relaisManquants: 0,
        coupuresRepasDues: 0,
        coupuresRepasManquantes: 0,
        message: 'Rien.',
      }),
    );
    expect(text(rien)).toContain('rien à organiser');
    expect(rien.nativeElement.querySelector('.pauses-alerte')).toBeNull();
  });

  it('resets its two switches in one call, writes them to the URL, and leaves the day alone', async () => {
    const fixture = await mount(rapport(), { date: '2026-07-11' });
    const view = fixture.componentInstance;
    view.withoutRelaisOnly.set(true);
    await fixture.whenStable();
    expect(view.modifiee()).toBe(true);
    // Its own key, next to the page's: `relais`, never the `vue` the page owns.
    expect(TestBed.inject(Location).path()).toContain('relais=sans');

    view.reinitialiser();
    TestBed.tick();
    await fixture.whenStable();

    expect(view.withoutRelaisOnly()).toBe(false);
    expect(view.modifiee()).toBe(false);
    expect(TestBed.inject(Location).path()).not.toContain('relais=');
    expect(text(fixture)).toContain('Carol Petit');
  });

  it('says what to do when the page could not read the report', async () => {
    expect(text(await mount(null))).toContain('Aucun planning persisté');
  });
});
