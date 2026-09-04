// What `pauses.spec.ts` cannot see: that the report reaches the screen grouped
// by stand, that the day selector moves and lands in the URL, that the two
// empty states and the missing-declaration warning show up.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { RapportPauses } from '../../core/models';
import { PausesPage } from './pauses-page';

function rapport(overrides: Partial<RapportPauses> = {}): RapportPauses {
  return {
    pauseSurPoste: true,
    journeesAnalysees: 3,
    pausesDues: 2,
    relaisManquants: 1,
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
                heureLimite: '19:00:00',
                dureeMinutes: 20,
                standId: 'JEUX',
                standNom: 'Village des jeux',
                relais: [{ animateurId: 'bob', nomComplet: 'Bob Durand' }],
                relaisDisponible: true
              }
            ]
          }
        ],
        pausesPlanifiees: [{ debut: '12:00:00', fin: '13:00:00', minutes: 60 }]
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
                heureLimite: '18:30:00',
                dureeMinutes: 30,
                standId: 'REF',
                standNom: 'Référencement',
                relais: [],
                relaisDisponible: false
              }
            ]
          }
        ],
        pausesPlanifiees: []
      }
    ],
    ...overrides
  };
}

describe('PausesPage', () => {
  const api = { get: vi.fn() };

  beforeEach(() => {
    api.get.mockReset();
  });

  async function mount(
    donnees: RapportPauses | (() => Promise<RapportPauses>),
    queryParams: Record<string, string> = {}
  ): Promise<ComponentFixture<PausesPage>> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
      ]
    });
    api.get.mockImplementation(typeof donnees === 'function' ? donnees : async () => donnees);
    const fixture = TestBed.createComponent(PausesPage);
    await fixture.whenStable();
    return fixture;
  }

  function texte(fixture: ComponentFixture<PausesPage>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function titresStands(fixture: ComponentFixture<PausesPage>): string[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.pauses-stand-titre'))
      .map((titre) => titre.textContent!.replace(/\s+/g, ' ').trim());
  }

  it('shows the first day grouped by stand, with the deadline and the relay', async () => {
    const fixture = await mount(rapport());

    expect(api.get).toHaveBeenCalledWith('/api/pauses');
    expect(titresStands(fixture)[0]).toContain('Village des jeux');
    const contenu = texte(fixture);
    expect(contenu).toContain('Alice Martin');
    expect(contenu).toContain('13:00–20:00');
    expect(contenu).toContain('19:00');
    expect(contenu).toContain('20 min');
    expect(contenu).toContain('Bob Durand');
    // The scheduled gap of the day is listed apart.
    expect(contenu).toContain('Pauses déjà planifiées');
    expect(contenu).toContain('12:00–13:00');
    // The other day stays out of sight.
    expect(contenu).not.toContain('Carol Petit');
  });

  it('moves to the next day, flags the missing relay and the minor, and lands the day in the URL', async () => {
    const fixture = await mount(rapport());

    const suivant = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[title="Jour suivant"]')!;
    suivant.click();
    await fixture.whenStable();

    const contenu = texte(fixture);
    expect(contenu).toContain('Carol Petit');
    expect(contenu).toContain('mineur');
    expect(contenu).toContain('30 min');
    expect(contenu).toContain("Personne d'autre sur le stand");
    expect(titresStands(fixture)[0]).toContain('1 sans relais');
    expect(suivant.disabled).toBe(true);
    // The day lands in the address, so the view can be shared and reloaded.
    expect(TestBed.inject(Location).path()).toContain('jour=2026-07-11');
  });

  it('opens on the day named in the URL', async () => {
    expect(texte(await mount(rapport(), { jour: '2026-07-11' }))).toContain('Carol Petit');
  });

  it('falls back on the first day for a day the report does not know', async () => {
    const fixture = await mount(rapport(), { jour: '2030-01-01' });
    expect(texte(fixture)).toContain('Alice Martin');
    expect(TestBed.inject(Location).path()).not.toContain('jour=');
  });

  it('warns when the on-post break is not declared, with a link to the legal parameters', async () => {
    const fixture = await mount(rapport({ pauseSurPoste: false }));

    const alerte = (fixture.nativeElement as HTMLElement).querySelector('.pauses-alerte');
    expect(alerte?.textContent).toContain("n'est pas déclarée");
    expect(alerte?.querySelector('a')?.getAttribute('href')).toBe('/constraints');
  });

  it('says so when nothing is persisted, and when nothing is to organise', async () => {
    expect(texte(await mount(rapport({ journeesAnalysees: 0, journees: [], pausesDues: 0 })))).toContain('Aucun planning persisté');

    const rien = await mount(rapport({ journees: [], pausesDues: 0, relaisManquants: 0, message: 'Rien.' }));
    expect(texte(rien)).toContain('rien à organiser');
    expect(rien.nativeElement.querySelector('.pauses-alerte')).toBeNull();
  });

  it('shows the error instead of an empty screen when the request fails', async () => {
    const fixture = await mount(async () => {
      throw new Error('boom');
    });

    expect(texte(fixture)).toContain('boom');
  });
});
