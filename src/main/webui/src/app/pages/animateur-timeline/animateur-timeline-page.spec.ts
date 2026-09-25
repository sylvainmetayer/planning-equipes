// The builders below are pure and tested as such. The rendering half at the end
// covers what the page does around them: which animateur it lands on (the URL
// carries the selection, so a shared link must open on the right person), and
// the three exports, which are the only actions of the screen — each of them
// hands out a file or a mail carrying someone's personal planning.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanningApi } from '../../core/api/planning-api';
import { ConsignesStore } from '../../core/consignes.store';
import { NotificationService } from '../../core/notification.service';
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
  AnimateurTimelinePage,
  buildAnimateurOptions,
  buildAnimateurTimeline,
  buildStandsSummary,
  exportFilename,
} from './animateur-timeline-page';

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

describe('buildAnimateurOptions', () => {
  it('lists each animateur once, sorted by display name', () => {
    const options = buildAnimateurOptions([
      poste({
        id: 'p1',
        creneau: creneau({ id: 1, jour: 1 }),
        stand: stand('S1'),
        animateur: animateur('B', 'Bob', 'Zed'),
      }),
      poste({
        id: 'p2',
        creneau: creneau({ id: 2, jour: 1 }),
        stand: stand('S2'),
        animateur: animateur('A', 'Alice', 'Young'),
      }),
      poste({
        id: 'p3',
        creneau: creneau({ id: 3, jour: 2 }),
        stand: stand('S1'),
        animateur: animateur('B', 'Bob', 'Zed'),
      }),
    ]);

    expect(options).toEqual([
      { id: 'A', label: 'Alice Young' },
      { id: 'B', label: 'Bob Zed' },
    ]);
  });

  it('disambiguates two animateurs sharing the same display name by appending their id', () => {
    const options = buildAnimateurOptions([
      poste({
        id: 'p1',
        creneau: creneau({ id: 1, jour: 1 }),
        stand: stand('S1'),
        animateur: animateur('id-1', 'Jean', 'Dupont'),
      }),
      poste({
        id: 'p2',
        creneau: creneau({ id: 2, jour: 1 }),
        stand: stand('S2'),
        animateur: animateur('id-2', 'Jean', 'Dupont'),
      }),
    ]);

    expect(options.map((option) => option.label)).toEqual([
      'Jean Dupont (id-1)',
      'Jean Dupont (id-2)',
    ]);
  });
});

describe('exportFilename', () => {
  it('builds a readable filename from the animateur display name', () => {
    expect(exportFilename([{ id: 'id-1', label: 'Jeanne Dupont' }], 'id-1', 'pdf')).toBe(
      'planning-Jeanne-Dupont.pdf',
    );
    expect(exportFilename([{ id: 'id-1', label: 'Jeanne Dupont' }], 'id-1', 'ics')).toBe(
      'planning-Jeanne-Dupont.ics',
    );
  });

  it('falls back on the id and strips path separators when the label is unusable', () => {
    expect(exportFilename([], 'a/b', 'ics')).toBe('planning-a-b.ics');
    expect(exportFilename([{ id: 'id-1', label: '///' }], 'id-1', 'pdf')).toBe(
      'planning-animateur.pdf',
    );
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

describe('AnimateurTimelinePage', () => {
  let fixture: ComponentFixture<AnimateurTimelinePage>;
  let analysesApi: { typologies: ReturnType<typeof vi.fn>; breaks: ReturnType<typeof vi.fn> };
  let planningApi: {
    exportForAnimateur: ReturnType<typeof vi.fn>;
    sendToAnimateur: ReturnType<typeof vi.fn>;
  };
  let notify: ReturnType<typeof vi.fn>;
  let replaceState: ReturnType<typeof vi.fn>;
  let planningState: {
    loadForDisplay: ReturnType<typeof vi.fn>;
    require: ReturnType<typeof vi.fn>;
  };
  let consignesStore: {
    reload: ReturnType<typeof vi.fn>;
    consigneOf: (date: string | null) => ConsigneEdition | null;
  };

  async function rendre(
    evenement: PlanningEvenement | null,
    options: { animateurEnParametre?: string | null; consignes?: ConsigneEdition[] } = {},
    analyses: { breaks?: () => unknown } = {},
  ): Promise<void> {
    consignesStore = {
      reload: vi.fn(async () => undefined),
      consigneOf: (date) => (options.consignes ?? []).find((each) => each.date === date) ?? null,
    };
    analysesApi = {
      typologies: vi.fn(async () => []),
      breaks: vi.fn(async () => analyses.breaks?.() ?? null),
    };
    planningApi = {
      exportForAnimateur: vi.fn(async () => 'Téléchargement démarré.'),
      sendToAnimateur: vi.fn(async () => ({ envoyes: 1, echecs: [] })),
    };
    notify = vi.fn();
    replaceState = vi.fn();
    planningState = {
      loadForDisplay: vi.fn(async () => evenement),
      require: vi.fn(async () => evenement),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: PlanningApi, useValue: planningApi },
        { provide: NotificationService, useValue: { notify } },
        { provide: PlanningStateService, useValue: planningState },
        { provide: ConsignesStore, useValue: consignesStore },
        { provide: Location, useValue: { path: () => '/timeline', replaceState } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: { get: () => options.animateurEnParametre ?? null } },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(AnimateurTimelinePage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Le geste de l'écran : choisir quelqu'un d'autre dans la liste. */
  function select(animateurId: string): void {
    (fixture.componentInstance as unknown as { selectAnimateur(id: string): void }).selectAnimateur(
      animateurId,
    );
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle),
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  it('lands on the first animateur when the URL names none', async () => {
    await rendre(twoAnimateurPlanning());

    expect(racine().querySelector('.timeline-animateur-select input')).not.toBeNull();
    expect(racine().querySelectorAll('.timeline-day-card')).toHaveLength(1);
    expect(racine().querySelector('.timeline-day-card h2')!.textContent!).toContain('Jour 1');
  });

  it('opens on the animateur the URL names, so a shared link points at the right person', async () => {
    await rendre(twoAnimateurPlanning(), { animateurEnParametre: 'a2' });

    // Bob holds one seat only, Alice two: the day list is what tells them apart.
    expect(racine().querySelectorAll('.timeline-block-list li')).toHaveLength(1);
  });

  it('keeps the selection in the URL without piling up history entries', async () => {
    await rendre(twoAnimateurPlanning());

    // Written straight to the address bar, never through a router navigation:
    // this page had its own copy of that effect until it joined the shared
    // helper. See docs/decisions/0018-ecrire-l-url-de-vue-sans-naviguer.md.
    expect(replaceState).toHaveBeenLastCalledWith('/timeline?animateur=a1');
  });

  it("suit le changement d'animateur dans l'URL, et c'est cette URL qui rouvre la même personne", async () => {
    // Le lien qu'on partage sur cet écran, c'est « regarde le planning
    // d'Untel » : changer de personne doit donc se voir dans la barre
    // d'adresse, et cette adresse doit rouvrir la même personne.
    await rendre(twoAnimateurPlanning());
    expect(replaceState).toHaveBeenLastCalledWith('/timeline?animateur=a1');

    select('a2');
    await fixture.whenStable();

    expect(replaceState).toHaveBeenLastCalledWith('/timeline?animateur=a2');

    // Et le retour : cette URL-là, rechargée, rouvre bien Bob — une seule
    // vacation, là où Alice en a deux.
    await rendre(twoAnimateurPlanning(), { animateurEnParametre: 'a2' });
    expect(racine().querySelectorAll('.timeline-block-list li')).toHaveLength(1);
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
    expect(
      racine().querySelector('.timeline-stands-card mat-card-subtitle')!.textContent!,
    ).toContain('2 stand(s)');
  });

  it('says what to do when there is no planning at all', async () => {
    await rendre({ postes: [] } as unknown as PlanningEvenement);

    expect(racine().textContent!).toContain('Lancez une résolution depuis la page Solveur');
    // Nothing to export: the three actions must not look available.
    expect(bouton('Exporter le PDF').disabled).toBe(true);
    expect(bouton("Exporter l'ICS").disabled).toBe(true);
    expect(bouton('Envoyer par e-mail').disabled).toBe(true);
  });

  it('shows the load error instead of an empty timeline', async () => {
    await rendre(null);
    planningState.loadForDisplay.mockRejectedValue(new Error('boom'));

    bouton('Actualiser').click();
    await fixture.whenStable();

    expect(racine().querySelector('[role="alert"]')!.textContent!).toContain('boom');
  });

  it('exports the displayed animateur, and only them', async () => {
    await rendre(twoAnimateurPlanning());

    bouton('Exporter le PDF').click();
    await fixture.whenStable();

    expect(planningApi.exportForAnimateur).toHaveBeenCalledOnce();
    const [format, animateurId, filename, corps, contentType] = planningApi.exportForAnimateur.mock
      .calls[0] as unknown as [string, string, string, unknown, string];
    expect(format).toBe('pdf');
    expect(animateurId).toBe('a1');
    // Named after the person, not after their id: the file lands in a mailbox.
    expect(filename).toBe('planning-Alice-Martin.pdf');
    // The planning goes as the request body: what is exported is what is shown.
    expect(corps).toEqual(await planningState.require.mock.results[0].value);
    expect(contentType).toBe('application/pdf');
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('reports an export failure instead of failing silently', async () => {
    await rendre(twoAnimateurPlanning());
    planningApi.exportForAnimateur.mockRejectedValue(new Error('serveur indisponible'));

    bouton("Exporter l'ICS").click();
    await fixture.whenStable();

    const dernier = notify.mock.calls.at(-1)![0];
    expect(dernier.variant).toBe('error');
    expect(dernier.message).toContain('serveur indisponible');
  });

  it('mails the planning of the displayed animateur', async () => {
    await rendre(twoAnimateurPlanning());

    bouton('Envoyer par e-mail').click();
    await fixture.whenStable();

    expect(planningApi.sendToAnimateur).toHaveBeenCalledWith('a1');
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

  it('still draws the tracks when the breaks cannot be read, and draws none without a selected animateur', async () => {
    await rendre(
      twoAnimateurPlanning(),
      {},
      {
        breaks: () => {
          throw new Error('HTTP 500');
        },
      },
    );
    expect(racine().querySelectorAll('.timeline-day-card').length).toBeGreaterThan(0);
    expect(racine().querySelector('.timeline-pause')).toBeNull();

    await rendre(
      { postes: [] } as unknown as PlanningEvenement,
      {},
      {
        breaks: () => ({
          journees: [],
          journeesAnalysees: 0,
          pausesDues: 0,
          relaisManquants: 0,
          message: '',
        }),
      },
    );
    expect(racine().querySelector('.timeline-pause')).toBeNull();
    expect(racine().querySelector('.timeline-day-card')).toBeNull();
  });
});
