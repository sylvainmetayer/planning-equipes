// The relecture bar of the Planning page (#712): one count of what to look at
// on the day, leading to the Diagnostic's problems, and the reading's menu.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { PosteAffectation, RapportPauses } from '../../core/models';
import { SessionStore } from '../../core/session.store';
import { DayState } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { RelectureBarre } from './relecture-barre';

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

/** One day of `a-p1` owing a break on S1 that nobody can relay. */
function pausesReport(): RapportPauses {
  return {
    journeesAnalysees: 1,
    pausesDues: 1,
    relaisManquants: 1,
    coupuresRepasDues: 0,
    coupuresRepasManquantes: 0,
    journees: [
      {
        animateurId: 'a-p1',
        nomComplet: 'Alice Martin',
        mineur: false,
        date: '2026-09-05',
        jour: 1,
        sequences: [
          {
            debut: '10:00',
            fin: '16:00',
            minutes: 360,
            pausesDues: [
              {
                debut: '13:00',
                fin: '13:20',
                heureLimite: '14:00',
                dureeMinutes: 20,
                standId: 'S1',
                standNom: 'Tir',
                relais: [],
                relaisDisponible: false,
                simultanee: false,
              },
            ],
          },
        ],
        pausesPlanifiees: [],
        coupuresRepas: [],
      },
    ],
    message: '',
  };
}

async function monter(options: { relus?: string[]; etat?: DayState; lectureSeule?: boolean } = {}) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: ValidationsStore,
        useValue: {
          acceptedDays: signal(new Set(options.relus ?? [])),
          etats: signal(
            (options.relus ?? []).map((jour) => ({
              jour,
              etat: options.etat ?? 'RELUE',
              valideLe: '2026-09-04T10:00:00Z',
            })),
          ),
        },
      },
      { provide: SessionStore, useValue: { lectureSeule: signal(options.lectureSeule ?? false) } },
      { provide: MatDialog, useValue: { open: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(RelectureBarre);
  fixture.componentRef.setInput('jour', '2026-09-05');
  fixture.componentRef.setInput('numero', 1);
  fixture.componentRef.setInput('postes', [poste('p1', true), poste('p2', false)]);
  fixture.componentRef.setInput('pauses', pausesReport());
  await fixture.whenStable();
  const compteur = (): HTMLAnchorElement =>
    (fixture.nativeElement as HTMLElement).querySelector('.relecture-barre a')!;
  return { fixture, compteur };
}

describe('RelectureBarre', () => {
  it('counts the empty seats and the breaks without relay, leading to the problems', async () => {
    const { compteur } = await monter();

    expect(compteur().textContent!.trim()).toBe('2 point(s) à relire');
    expect(compteur().classList).toContain('relecture-compteur-alerte');
    expect(compteur().getAttribute('href')).toBe('/diagnostic?onglet=problemes');
  });

  it('says whether the day was read, in the button of its menu', async () => {
    const aRelire = await monter();
    expect(aRelire.fixture.nativeElement.textContent).toContain('À relire');

    const relue = await monter({ relus: ['2026-09-05'] });
    expect(relue.fixture.nativeElement.textContent).toContain('Relue le');
  });

  it('says a frozen day is frozen', async () => {
    const { fixture } = await monter({ relus: ['2026-09-05'], etat: 'FIGEE' });

    expect(fixture.nativeElement.textContent).toContain('Figée');
  });

  it('gives a lecteur the state and no button', async () => {
    const { fixture } = await monter({ relus: ['2026-09-05'], lectureSeule: true });
    const element = fixture.nativeElement as HTMLElement;

    expect(element.textContent).toContain('Relue le');
    expect(element.querySelector('button')).toBeNull();
  });
});
