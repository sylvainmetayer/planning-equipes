// The relecture bar of the Planning page (#712): four chips counting what to
// look at on the day, each narrowing the rendering on what it counts.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { JourneesApi } from '../../core/api/journees-api';
import { PosteAffectation, RapportPauses } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { PastilleRelecture, RelectureBarre } from './relecture-barre';

const STAND = { id: 'S1', nom: 'Tir' } as PosteAffectation['stand'];
const CRENEAU = { id: 1, jour: 1, date: '2026-09-05', heureDebut: '10:00', heureFin: '12:00' };

function poste(id: string, occupe: boolean): PosteAffectation {
  return {
    id,
    stand: STAND,
    creneau: CRENEAU,
    animateur: occupe ? ({ id: `a-${id}` } as PosteAffectation['animateur']) : null,
  };
}

async function monter(options: { actif?: PastilleRelecture; relus?: string[] } = {}) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: JourneesApi,
        useValue: {
          changements: vi.fn(async () => ({
            referenceDisponible: true,
            parVacation: [{}, {}, {}],
          })),
        },
      },
      {
        provide: ValidationsStore,
        useValue: {
          acceptedDays: signal(new Set(options.relus ?? [])),
          validations: signal(
            (options.relus ?? []).map((jour) => ({
              id: 'v',
              jour,
              valideLe: '2026-09-04T10:00:00Z',
            })),
          ),
        },
      },
      {
        provide: VerrouillageStore,
        useValue: {
          estJourVerrouille: () => false,
          estStandVerrouille: () => false,
          estCreneauVerrouille: (id: number | undefined) => id === 1,
        },
      },
      { provide: MatDialog, useValue: { open: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(RelectureBarre);
  fixture.componentRef.setInput('jour', '2026-09-05');
  fixture.componentRef.setInput('numero', 1);
  fixture.componentRef.setInput('postes', [poste('p1', true), poste('p2', false)]);
  fixture.componentRef.setInput('pauses', {
    journees: [
      {
        jour: 1,
        animateurId: 'a-p1',
        sequences: [{ pausesDues: [{ relaisDisponible: false, standId: 'S1' }] }],
      },
    ],
  } as unknown as RapportPauses);
  fixture.componentRef.setInput('active', options.actif ?? 'aucune');
  await fixture.whenStable();
  const pastilles = (): HTMLButtonElement[] =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.relecture-pastille'));
  return { fixture, pastilles };
}

describe('RelectureBarre', () => {
  it('counts the empty seats, the breaks without relay, the locked seats and the changes', async () => {
    const { pastilles } = await monter();

    expect(pastilles().map((pastille) => pastille.textContent!.trim())).toEqual([
      '1 siège(s) vide(s)',
      '1 pause(s) sans relais',
      '2 siège(s) verrouillé(s)',
      '3 changement(s)',
    ]);
    expect(pastilles()[0].classList).toContain('relecture-pastille-alerte');
  });

  it('asks the page to narrow the rendering, and to widen it back on the active chip', async () => {
    const { fixture, pastilles } = await monter({ actif: 'verrous' });
    const emis: PastilleRelecture[] = [];
    fixture.componentInstance.pastille.subscribe((pastille) => emis.push(pastille));

    pastilles()[0].click();
    pastilles()[2].click();

    expect(emis).toEqual(['vides', 'aucune']);
    expect(pastilles()[2].getAttribute('aria-pressed')).toBe('true');
  });

  it('says whether the day was read, in the button of its menu', async () => {
    const aRelire = await monter();
    expect(aRelire.fixture.nativeElement.textContent).toContain('À relire');

    const relue = await monter({ relus: ['2026-09-05'] });
    expect(relue.fixture.nativeElement.textContent).toContain('Relue le');
  });
});
