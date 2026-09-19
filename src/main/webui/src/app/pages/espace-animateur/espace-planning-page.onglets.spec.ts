// The three tabs of the espace (issue #615), rendered: the day strip, the
// frieze and the teammate search are read with a thumb, and what they are worth
// is what ends up on screen — not what the class computes.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { EspaceAnimateurView, PosteAnimateurView } from '../../core/models';
import { typologieColorClass } from '../../core/typologie-colors';
import { EspacePlanningPage } from './espace-planning-page';

function poste(overrides: Partial<PosteAnimateurView> = {}): PosteAnimateurView {
  return {
    date: '2026-07-11',
    standId: 'stand-1',
    standNom: 'Construction',
    creneauId: 1,
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    coequipiers: [],
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    typologieId: 'CONSTRUCTION',
    typologieLibelle: 'Jeux de construction',
    ...overrides,
  };
}

function view(overrides: Partial<EspaceAnimateurView> = {}): EspaceAnimateurView {
  return {
    joursRepos: [],
    animateurId: 'alice',
    prenom: 'Alice',
    nom: 'Martin',
    publieLe: '2026-07-01T10:00:00Z',
    foireOuverte: true,
    postes: [poste()],
    collegues: [],
    statutConfirmation: 'CONFIRME',
    confirmeLe: '2026-07-02T10:00:00Z',
    foireOuvreLe: null,
    foireFermeLe: null,
    abonnementToken: 'abo-1',
    consignes: [],
    pauses: [],
    changements: [],
    changementsLe: null,
    dateDuJourFigee: null,
    heureDuJourFigee: null,
    ...overrides,
  } as EspaceAnimateurView;
}

describe('EspacePlanningPage — les trois onglets', () => {
  const espaceView = signal<EspaceAnimateurView | null>(view());
  let fixture: ComponentFixture<EspacePlanningPage>;
  let params: BehaviorSubject<ParamMap>;

  async function rendre(
    vue: EspaceAnimateurView | null,
    options: { quand?: Date; onglet?: string; jour?: string } = {},
  ): Promise<void> {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(options.quand ?? new Date(2026, 6, 11, 11, 0));
    espaceView.set(vue);
    params = new BehaviorSubject<ParamMap>(
      convertToParamMap({
        ...(options.onglet ? { onglet: options.onglet } : {}),
        ...(options.jour ? { jour: options.jour } : {}),
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: EspaceAnimateurService,
          useValue: {
            view: espaceView,
            jeton: signal<string | null>('jeton-1'),
            regenererAbonnement: vi.fn(async () => undefined),
            confirmerPlanning: vi.fn(async () => undefined),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: params.value }, queryParamMap: params },
        },
      ],
    });
    fixture = TestBed.createComponent(EspacePlanningPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function textOf(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  function onglets(): string[] {
    return Array.from(racine().querySelectorAll('mat-button-toggle')).map((each) =>
      Array.from(each.querySelectorAll('mat-icon'))
        .reduce((libelle, icone) => libelle.replace(icone.textContent!, ''), each.textContent!)
        .trim(),
    );
  }

  async function cliquerOnglet(nom: string): Promise<void> {
    const onglet = Array.from(racine().querySelectorAll('mat-button-toggle')).find((each) =>
      each.textContent!.includes(nom),
    );
    expect(onglet, `onglet « ${nom} » absent`).toBeDefined();
    (onglet as HTMLElement).querySelector('button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function bande(): HTMLButtonElement[] {
    return Array.from(racine().querySelectorAll('.espace-bande-jour'));
  }

  const troisJours = view({
    postes: [
      poste({ date: '2026-07-10', standId: 'stand-2', standNom: 'Molkky' }),
      poste({ date: '2026-07-11', creneauId: 2 }),
      poste({
        date: '2026-07-12',
        creneauId: 3,
        standId: 'stand-3',
        standNom: 'Kubb',
        heureDebut: '14:00:00',
        heureFin: '18:00:00',
        typologieId: 'EXTERIEUR',
        typologieLibelle: 'Jeux extérieurs',
      }),
    ],
    joursRepos: ['2026-07-13'],
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(async () => {
    await rendre(troisJours);
  });

  it('nomme ses trois onglets et ouvre sur la journée', () => {
    expect(onglets()).toEqual(['Jour', 'Aperçu', 'Coéquipiers']);
    expect(racine().querySelector('.espace-journee')).not.toBeNull();
    expect(racine().querySelector('.espace-frise')).toBeNull();
    expect(racine().querySelector('.espace-equipe')).toBeNull();
  });

  /** Every chip is a real button, and its whole label is read out loud. */
  it("couvre tous les jours de l'édition, repos compris, et marque aujourd'hui", () => {
    expect(bande()).toHaveLength(4);
    expect(bande()[3].classList).toContain('espace-bande-jour-repos');
    expect(bande()[1].classList).toContain('espace-bande-jour-aujourdhui');
    expect(bande()[1].classList).toContain('espace-bande-jour-actif');
    expect(bande()[0].getAttribute('aria-label')).toMatch(/2 h$/);
    expect(bande()[3].getAttribute('aria-label')).toMatch(/repos$/);
  });

  it('marque les jours sous consigne, dans la bande comme dans la frise', async () => {
    await rendre(
      view({
        ...troisJours,
        consignes: [
          {
            date: '2026-07-12',
            fermetureDebut: '12:00:00',
            fermetureFin: '16:00:00',
            motif: 'arrêté préfectoral · canicule',
          },
        ],
      } as EspaceAnimateurView),
    );

    expect(bande()[2].querySelector('.espace-bande-consigne')).not.toBeNull();
    expect(bande()[0].querySelector('.espace-bande-consigne')).toBeNull();
  });

  it('ouvre la journée touchée dans la bande', async () => {
    bande()[2].click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(textOf()).toContain('Kubb');
    expect(bande()[2].classList).toContain('espace-bande-jour-actif');
  });

  /** Before the event there is no today, so the day strip opens on the first day. */
  it("ouvre sur le premier jour avant l'événement", async () => {
    await rendre(troisJours, { quand: new Date(2026, 5, 1, 9, 0) });

    expect(bande()[0].classList).toContain('espace-bande-jour-actif');
    expect(racine().querySelector('.espace-maintenant-poste')).toBeNull();
  });

  it('suit l’adresse plutôt que de la lire une fois', async () => {
    params.next(convertToParamMap({ onglet: 'coequipiers' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(racine().querySelector('.espace-equipe')).not.toBeNull();
  });

  it("revient à la journée quand l'adresse nomme un onglet inconnu", async () => {
    await rendre(troisJours, { onglet: 'planning' });

    expect(racine().querySelector('.espace-journee')).not.toBeNull();
  });

  /* --------------------------- The « Aperçu » tab --------------------------- */

  it('résume le planning en quatre chiffres et une ligne par jour', async () => {
    await cliquerOnglet('Aperçu');

    const chiffres = Array.from(racine().querySelectorAll('.espace-stats dd')).map((each) =>
      each.textContent!.trim(),
    );
    // 2 h + 2 h + 4 h, three timeslots, three stands, three worked days.
    expect(chiffres).toEqual(['8', '3', '3', '3']);
    expect(racine().querySelectorAll('.espace-frise-ligne')).toHaveLength(4);
  });

  it('colore la frise par typologie, avec l’attribution partagée', async () => {
    await cliquerOnglet('Aperçu');

    const barres = Array.from(racine().querySelectorAll('.espace-frise-barre'));
    expect(barres[0].classList).toContain(typologieColorClass('CONSTRUCTION'));
    expect(barres[2].classList).toContain(typologieColorClass('EXTERIEUR'));
    // And the legend names what the colour shows: colour alone is never an
    // information carrier (WCAG 1.4.1).
    expect(textOf()).toContain('Jeux de construction');
    expect(textOf()).toContain('Jeux extérieurs');
  });

  it('ouvre la journée touchée dans l’onglet Jour', async () => {
    await cliquerOnglet('Aperçu');
    racine().querySelectorAll<HTMLButtonElement>('.espace-frise-bouton')[2].click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(racine().querySelector('.espace-journee')).not.toBeNull();
    expect(textOf()).toContain('Kubb');
  });

  /* ------------------------ The « Coéquipiers » tab ------------------------ */

  it('classe les coéquipiers par nombre de créneaux partagés', async () => {
    await rendre(
      view({
        postes: [
          poste({ date: '2026-07-10', coequipiers: ['Zoé Durand', 'Alice Martin'] }),
          poste({ date: '2026-07-11', creneauId: 2, coequipiers: ['Alice Martin'] }),
        ],
      }),
    );
    await cliquerOnglet('Coéquipiers');

    const noms = Array.from(racine().querySelectorAll('.espace-equipe-nom span:first-child')).map(
      (each) => each.textContent!.trim(),
    );
    expect(noms).toEqual(['Alice Martin', 'Zoé Durand']);
  });

  it('cherche un nom sans se soucier des accents ni de la casse', async () => {
    await rendre(view({ postes: [poste({ coequipiers: ['Zoé Durand', 'Alice Martin'] })] }));
    await cliquerOnglet('Coéquipiers');

    const champ = racine().querySelector<HTMLInputElement>('.espace-equipe-recherche input')!;
    champ.value = 'ZOE';
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(textOf()).toContain('Zoé Durand');
    expect(textOf()).not.toContain('Alice Martin');

    champ.value = 'Bernard';
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(textOf()).toContain('Personne avec ce nom dans votre planning');
  });

  /* ------------------ What was not meant to disappear ------------------ */

  it('garde le bouton d’échange sous les trois onglets', async () => {
    for (const onglet of ['Jour', 'Aperçu', 'Coéquipiers']) {
      await cliquerOnglet(onglet);
      expect(racine().querySelector('.espace-action-epinglee a')!.getAttribute('href')).toBe(
        '/animateur/jeton-1/echanges',
      );
    }
  });

  /**
   * Subscribing before the publication is the right move: the feed fills up on
   * its own. An empty espace must therefore not hide the subscription band.
   */
  it("offre l'abonnement même quand rien n'est encore publié", async () => {
    await rendre(view({ publieLe: null, postes: [], joursRepos: [] }));

    expect(textOf()).toContain('Aucune affectation pour le moment');

    await cliquerOnglet('Aperçu');
    expect(racine().querySelector('.espace-agenda-abonnement')).not.toBeNull();
    expect(racine().querySelector('.espace-frise')).toBeNull();
  });
});
