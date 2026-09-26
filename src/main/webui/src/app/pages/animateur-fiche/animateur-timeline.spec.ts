// The builders below are pure and tested as such. The rendering half at the end
// covers what the « Planning » section of the fiche draws around them: the
// person's days, what the plan says about them, and every shift a link to the
// Journée with its seat open.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ConsignesStore } from '../../core/consignes.store';
import { PlanningStateService } from '../../core/planning-state.service';
import {
  Animateur,
  ConsigneEdition,
  Creneau,
  PlanningEvenement,
  PosteAffectation,
  Stand,
} from '../../core/models';
import {
  AnimateurTimeline,
  buildAnimateurTimeline,
  buildStandsSummary,
  exportFilename,
} from './animateur-timeline';

function creneau(overrides: Partial<Creneau> & { id: number; jour: number }): Creneau {
  return { date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00', ...overrides };
}

function stand(id: string, typologiesProposees: string[] = []): Stand {
  return {
    id,
    nom: id,
    typologiesProposees,
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function animateur(id: string, prenom = id, nom = ''): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '2000-01-01',
    manager: false,
    competences: {},
    joursIndisponibles: [],
    souhaits: [],
  };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

describe('buildAnimateurTimeline', () => {
  it('positions a single vacation spanning the whole amplitude at 0% offset and 100% width', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1, heureDebut: '09:00', heureFin: '12:00' }),
          stand: stand('S1'),
          animateur: animateur('A'),
        }),
      ],
      'A',
    );

    expect(days).toHaveLength(1);
    expect(days[0].blocks).toEqual([
      expect.objectContaining({
        heureDebut: '09:00',
        heureFin: '12:00',
        offsetPercent: 0,
        widthPercent: 100,
      }),
    ]);
    expect(days[0].gaps).toHaveLength(0);
  });

  it('lists the other animateurs of the same stand line as teammates, and only them', () => {
    const c1 = creneau({ id: 1, jour: 1, heureDebut: '09:00', heureFin: '12:00' });
    const s1 = stand('S1');
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('A', 'Ada', 'Lovelace') }),
        poste({ id: 'p2', creneau: c1, stand: s1, animateur: animateur('B', 'Alan', 'Turing') }),
        poste({ id: 'p3', creneau: c1, stand: s1, animateur: null }), // unfilled seat: nobody to name
        // Same créneau, another stand: not a teammate.
        poste({
          id: 'p4',
          creneau: c1,
          stand: stand('S2'),
          animateur: animateur('C', 'Grace', 'Hopper'),
        }),
      ],
      'A',
    );

    expect(days[0].blocks[0].coequipiers).toEqual(['Alan Turing']);
  });

  it('leaves the teammate list empty for a stand held alone', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('S1'),
          animateur: animateur('A'),
        }),
      ],
      'A',
    );

    expect(days[0].blocks[0].coequipiers).toEqual([]);
  });

  it('does not pair two segments of the same stand and créneau split by a partial closure', () => {
    // A mid-créneau closure splits the stand into two windows: whoever holds
    // the morning half never meets whoever holds the afternoon half.
    const c1 = creneau({ id: 1, jour: 1, heureDebut: '09:00', heureFin: '18:00' });
    const s1 = stand('S1');
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: c1,
          stand: s1,
          animateur: animateur('A'),
          heureDebutEffective: '09:00',
          heureFinEffective: '12:00',
        }),
        poste({
          id: 'p2',
          creneau: c1,
          stand: s1,
          animateur: animateur('B'),
          heureDebutEffective: '14:00',
          heureFinEffective: '18:00',
        }),
      ],
      'A',
    );

    expect(days[0].blocks[0].coequipiers).toEqual([]);
  });

  it('inserts a gap between two vacations, sized proportionally to the amplitude', () => {
    // 08:00-10:00 then 11:00-13:00: amplitude is 08:00-13:00 (300 min), the
    // 1h gap (10:00-11:00, 60 min) should land at 40% offset / 20% width.
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1, heureDebut: '08:00', heureFin: '10:00' }),
          stand: stand('S1'),
          animateur: animateur('A'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 1, heureDebut: '11:00', heureFin: '13:00' }),
          stand: stand('S2'),
          animateur: animateur('A'),
        }),
      ],
      'A',
    );

    expect(days[0].blocks).toHaveLength(2);
    expect(days[0].gaps).toEqual([
      expect.objectContaining({ dureeMinutes: 60, offsetPercent: 40, widthPercent: 20 }),
    ]);
    expect(days[0].amplitudeDebut).toBe('08:00');
    expect(days[0].amplitudeFin).toBe('13:00');
  });

  it('produces no gap for two back-to-back vacations', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1, heureDebut: '08:00', heureFin: '10:00' }),
          stand: stand('S1'),
          animateur: animateur('A'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 1, heureDebut: '10:00', heureFin: '12:00' }),
          stand: stand('S2'),
          animateur: animateur('A'),
        }),
      ],
      'A',
    );

    expect(days[0].gaps).toHaveLength(0);
  });

  it('treats a "00:00" end of a vacation as midnight (end of this event day), not the start of the next', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1, heureDebut: '22:00', heureFin: '00:00' }),
          stand: stand('S1'),
          animateur: animateur('A'),
        }),
      ],
      'A',
    );

    expect(days[0].amplitudeFin).toBe('00:00');
    expect(days[0].blocks[0]).toMatchObject({ widthPercent: 100 });
  });

  it('prefers heureDebutEffective/heureFinEffective over the créneau window (issue #60 partial closures)', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1, heureDebut: '08:00', heureFin: '20:00' }),
          stand: stand('S1'),
          animateur: animateur('A'),
          heureDebutEffective: '14:00',
          heureFinEffective: '16:00',
        }),
      ],
      'A',
    );

    expect(days[0].blocks[0]).toMatchObject({ heureDebut: '14:00', heureFin: '16:00' });
  });

  it('groups vacations by event day and orders days chronologically', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 2 }),
          stand: stand('S1'),
          animateur: animateur('A'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 1 }),
          stand: stand('S2'),
          animateur: animateur('A'),
        }),
      ],
      'A',
    );

    expect(days.map((day) => day.jour)).toEqual([1, 2]);
  });

  it('excludes other animateurs and unassigned postes', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('S1'),
          animateur: animateur('B'),
        }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1 }), stand: stand('S2') }),
      ],
      'A',
    );

    expect(days).toHaveLength(0);
  });
});

describe('exportFilename', () => {
  it('builds a readable filename from the animateur display name', () => {
    expect(exportFilename('Jeanne Dupont', 'pdf')).toBe('planning-Jeanne-Dupont.pdf');
    expect(exportFilename('Jeanne Dupont', 'ics')).toBe('planning-Jeanne-Dupont.ics');
  });

  it('strips path separators, and falls back on a word when nothing is left', () => {
    expect(exportFilename('a/b', 'ics')).toBe('planning-a-b.ics');
    expect(exportFilename('///', 'pdf')).toBe('planning-animateur.pdf');
  });
});

describe('buildStandsSummary', () => {
  it('counts each stand once even when the animateur returns to it on several days', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('Zebre'),
          animateur: animateur('id-1', 'Jean', 'Dupont'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 2 }),
          stand: stand('Alpha'),
          animateur: animateur('id-1', 'Jean', 'Dupont'),
        }),
        poste({
          id: 'p3',
          creneau: creneau({ id: 3, jour: 3 }),
          stand: stand('Alpha'),
          animateur: animateur('id-1', 'Jean', 'Dupont'),
        }),
      ],
      'id-1',
    );

    expect(buildStandsSummary(days).count).toBe(2);
    expect(buildStandsSummary(days).stands.map((stand) => stand.nom)).toEqual(['Alpha', 'Zebre']);
  });

  it('returns an empty summary when the animateur has no day at all', () => {
    expect(buildStandsSummary([])).toEqual({ count: 0, typologieCount: 0, stands: [], legend: [] });
  });

  it('counts each game typologie once across every stand covered', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('Zebre', ['AMBIANCE']),
          animateur: animateur('id-1'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 2 }),
          stand: stand('Alpha', ['AMBIANCE', 'STRATEGIE']),
          animateur: animateur('id-1'),
        }),
      ],
      'id-1',
    );

    const summary = buildStandsSummary(
      days,
      new Map([
        ['AMBIANCE', 'Ambiance'],
        ['STRATEGIE', 'Stratégie'],
      ]),
    );

    expect(summary.count).toBe(2);
    expect(summary.typologieCount).toBe(2);
    expect(summary.legend.map((item) => item.label)).toEqual(['Ambiance', 'Stratégie']);
    expect(summary.stands[0].typologies).toEqual(['Ambiance', 'Stratégie']);
    expect(summary.stands[0].tooltip).toContain('Ambiance, Stratégie');
  });

  it('gives every stand of the same typologie the same colour, and a stand without typologie the neutral one', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('Alpha', ['AMBIANCE']),
          animateur: animateur('id-1'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 2 }),
          stand: stand('Beta', ['AMBIANCE']),
          animateur: animateur('id-1'),
        }),
        poste({
          id: 'p3',
          creneau: creneau({ id: 3, jour: 3 }),
          stand: stand('Gamma'),
          animateur: animateur('id-1'),
        }),
      ],
      'id-1',
    );

    const summary = buildStandsSummary(days);

    expect(summary.stands[0].colorClass).toBe(summary.stands[1].colorClass);
    expect(summary.stands[2].colorClass).toBe('typologie-color-none');
  });
});

function heatwaveConsigne(date: string): ConsigneEdition {
  return {
    date,
    fermetureDebut: '12:00',
    fermetureFin: '18:00',
    motif: 'Plan canicule',
    prereglage: null,
    fenetres: [],
    ouvertures: [],
    repas: null,
    creneauxAjoutes: [],
    creeLe: null,
    modifieLe: null,
  };
}

function twoAnimateurPlanning(): PlanningEvenement {
  const morning = creneau({ id: 1, jour: 1 });
  const afternoon = creneau({ id: 2, jour: 1, heureDebut: '14:00', heureFin: '18:00' });
  return {
    postes: [
      poste({
        id: 'p1',
        creneau: morning,
        stand: stand('Tir'),
        animateur: animateur('a1', 'Alice', 'Martin'),
      }),
      poste({
        id: 'p2',
        creneau: morning,
        stand: stand('Tir'),
        animateur: animateur('a2', 'Bob', 'Durand'),
      }),
      poste({
        id: 'p3',
        creneau: afternoon,
        stand: stand('Dixit'),
        animateur: animateur('a1', 'Alice', 'Martin'),
      }),
    ],
  } as unknown as PlanningEvenement;
}

describe('AnimateurTimeline', () => {
  let fixture: ComponentFixture<AnimateurTimeline>;
  let planningState: { loadForDisplay: ReturnType<typeof vi.fn> };

  async function rendre(
    evenement: PlanningEvenement | null,
    options: { animateurId?: string; consignes?: ConsigneEdition[] } = {},
    analyses: { breaks?: () => unknown; walks?: () => unknown } = {},
  ): Promise<void> {
    const consignesStore = {
      reload: vi.fn(async () => undefined),
      consigneOf: (date: string | null) =>
        (options.consignes ?? []).find((each) => each.date === date) ?? null,
    };
    const analysesApi = {
      typologies: vi.fn(async () => []),
      breaks: vi.fn(async () => analyses.breaks?.() ?? null),
      walks: vi.fn(async () => analyses.walks?.() ?? null),
    };
    planningState = {
      loadForDisplay: vi.fn(async () => {
        if (!evenement) {
          throw new Error('boom');
        }
        return evenement;
      }),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: PlanningStateService, useValue: planningState },
        { provide: ConsignesStore, useValue: consignesStore },
      ],
    });
    fixture = TestBed.createComponent(AnimateurTimeline);
    fixture.componentRef.setInput('animateurId', options.animateurId ?? 'a1');
    await fixture.whenStable();
    // The plan is read on init, outside what `whenStable` waits for.
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(planningState.loadForDisplay).toHaveBeenCalled();
      expect(racine().querySelector('mat-progress-bar')).toBeNull();
    });
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('reads the plan again when the fiche says one of its gestures moved it', async () => {
    await rendre(twoAnimateurPlanning(), { animateurId: 'a2' });
    expect(racine().querySelectorAll('.timeline-day')).toHaveLength(1);

    // Bob's seat was freed meanwhile: the next read no longer seats him.
    planningState.loadForDisplay.mockResolvedValue({
      ...twoAnimateurPlanning(),
      postes: twoAnimateurPlanning().postes.filter((poste) => poste.animateur?.id !== 'a2'),
    });
    fixture.componentRef.setInput('version', 1);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(planningState.loadForDisplay).toHaveBeenCalledTimes(2);
      expect(racine().querySelectorAll('.timeline-day')).toHaveLength(0);
    });
  });

  it('draws the days of the person it is given, and of nobody else', async () => {
    await rendre(twoAnimateurPlanning(), { animateurId: 'a2' });

    // Bob holds one seat only, Alice two: the day list is what tells them apart.
    expect(racine().querySelectorAll('.timeline-day')).toHaveLength(1);
    expect(racine().querySelectorAll('.timeline-block-list li')).toHaveLength(1);
  });

  it('links every shift to the Journée on its day, its seat open in the Siège panel', async () => {
    const evenement = twoAnimateurPlanning();
    await rendre(evenement);

    const liens = Array.from(racine().querySelectorAll<HTMLAnchorElement>('a.timeline-block-link'));
    expect(liens).toHaveLength(2);
    const premier = evenement.postes.find((each) => each.animateur?.id === 'a1')!;
    expect(liens[0].getAttribute('href')).toBe(
      `/journee?date=${premier.creneau!.date}&siege=${premier.id}`,
    );
  });

  it('tells the animateur when their day is under consigne, as the Journée tab and the PDF do', async () => {
    await rendre(twoAnimateurPlanning(), { consignes: [heatwaveConsigne('2026-08-01')] });

    const note = racine().querySelector('.timeline-consigne-note')!.textContent!;
    expect(note).toContain('sous consigne');
    expect(note).toContain('Plan canicule');
    expect(note).toContain('12h–18h');
  });

  it('says nothing about consignes on an ordinary day', async () => {
    await rendre(twoAnimateurPlanning(), { consignes: [heatwaveConsigne('2026-08-02')] });

    expect(racine().querySelector('.timeline-consigne-note')).toBeNull();
  });

  it('names the teammates of each seat, and says so when there are none', async () => {
    await rendre(twoAnimateurPlanning());

    const lignes = Array.from(racine().querySelectorAll('.timeline-block-list li')).map((each) =>
      each.textContent!.replace(/\s+/g, ' ').trim(),
    );
    expect(lignes[0]).toContain('avec Bob Durand');
    expect(lignes[1]).toContain('seul(e) sur ce stand');
  });

  it('recaps the stands to cover above the days', async () => {
    await rendre(twoAnimateurPlanning());

    expect(
      Array.from(racine().querySelectorAll('.timeline-stand-chip')).map((each) =>
        each.textContent!.trim(),
      ),
    ).toEqual(['Dixit', 'Tir']);
    expect(racine().querySelector('.timeline-stands-summary')!.textContent!).toContain(
      '2 stand(s)',
    );
  });

  it('says so when the person holds no seat', async () => {
    await rendre({ postes: [] } as unknown as PlanningEvenement);

    expect(racine().textContent!).toContain(
      "Cet animateur n'a aucun poste dans le planning actuel.",
    );
  });

  it('shows the load error instead of an empty timeline', async () => {
    await rendre(null);

    expect(racine().querySelector('[role="alert"]')!.textContent!).toContain('boom');
    expect(racine().querySelector('.timeline-day')).toBeNull();
  });

  it('draws the breaks of the shown days on their track and lists them, the relay-less one flagged', async () => {
    const evenement = twoAnimateurPlanning();
    const rapport = {
      journeesAnalysees: 1,
      pausesDues: 1,
      relaisManquants: 1,
      message: '',
      journees: [
        {
          animateurId: 'a1',
          nomComplet: 'Alice',
          mineur: false,
          date: evenement.postes[0].creneau!.date,
          jour: evenement.postes[0].creneau!.jour,
          sequences: [
            {
              debut: '09:00:00',
              fin: '12:00:00',
              minutes: 180,
              pausesDues: [
                {
                  debut: '11:40:00',
                  fin: '12:00:00',
                  heureLimite: '12:00:00',
                  dureeMinutes: 20,
                  standId: 's',
                  standNom: evenement.postes[0].stand!.nom,
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
    };
    await rendre(evenement, {}, { breaks: () => rapport });

    const segment = racine().querySelector('.timeline-pause');
    expect(segment).not.toBeNull();
    expect(segment!.classList.contains('timeline-pause-alerte')).toBe(true);
    expect(segment!.getAttribute('aria-label')).toContain("personne d'autre sur le stand");
    expect(racine().querySelector('.timeline-pause-item')?.textContent).toContain(
      'Pause 11:40 – 12:00',
    );
  });

  it('draws a red chevron between two seats the walk does not fit, and lists it', async () => {
    const evenement = twoAnimateurPlanning();
    await rendre(
      evenement,
      {},
      {
        walks: () => ({
          walkingSpeedKmH: 4,
          detourFactor: 1.3,
          toleranceMinutes: 5,
          geolocated: true,
          walks: [
            {
              animateurId: 'a1',
              date: evenement.postes[0].creneau!.date,
              end: '10:00:00',
              start: '10:10:00',
              distanceMetres: 1000,
              walkMinutes: 20,
              gapMinutes: 10,
              missingMinutes: 5,
              walkOnBreak: false,
            },
          ],
        }),
      },
    );

    const chevron = racine().querySelector('.timeline-walk');
    expect(chevron).not.toBeNull();
    expect(chevron!.classList.contains('timeline-walk-tight')).toBe(true);
    expect(racine().querySelector('.timeline-walk-item')?.textContent).toContain(
      '20 min à pied, 10 min de battement',
    );
  });

  it('still draws the tracks when the breaks cannot be read', async () => {
    await rendre(
      twoAnimateurPlanning(),
      {},
      {
        breaks: () => {
          throw new Error('HTTP 500');
        },
      },
    );
    expect(racine().querySelectorAll('.timeline-day').length).toBeGreaterThan(0);
    expect(racine().querySelector('.timeline-pause')).toBeNull();
  });
});
