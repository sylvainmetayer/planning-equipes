// The « Emporter mon planning » band of the espace planning page (issue #324).
//
// It is rendered rather than driven through the class on purpose: what has to
// be frozen is what the animateur reads on screen after a sequence of copy
// attempts. « Adresse copiée » and « Copie impossible : sélectionnez l'adresse
// ci-dessus » are mutually exclusive instructions — the second one asks for a
// fallback the first one says is pointless — and this screen is mostly read on
// a phone, where a refused clipboard (Chrome's `NotAllowedError` on a document
// that lost focus) is a routine outcome, not an edge case.
//
// The band's ORDER is frozen here too: the subscription comes before the day
// cards and before the two one-shot files. At the bottom of the page it was
// never reached on a phone, and a snapshot file silently going stale is the
// defect issue #324 exists to remove — so a future edit must not quietly send
// it back down or demote it below the download.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { EspaceAnimateurView, PauseAnimateurView, PosteAnimateurView } from '../../core/models';
import { EspacePlanningPage } from './espace-planning-page';

function view(overrides: Partial<EspaceAnimateurView> = {}): EspaceAnimateurView {
  return {
    joursRepos: [],
    animateurId: 'alice',
    prenom: 'Alice',
    nom: 'Martin',
    publieLe: '2026-07-01T10:00:00Z',
    foireOuverte: true,
    postes: [],
    collegues: [],
    statutConfirmation: 'NON_VU',
    confirmeLe: null,
    foireOuvreLe: null,
    foireFermeLe: null,
    abonnementToken: 'abo-1',
    pauses: [],
    dateDuJourFigee: null,
    ...overrides,
  } as EspaceAnimateurView;
}

function poste(overrides: Partial<PosteAnimateurView> = {}): PosteAnimateurView {
  return {
    date: '2026-07-10',
    standId: 'stand-1',
    standNom: 'Stand un',
    creneauId: 1,
    heureDebut: '10:00',
    heureFin: '12:00',
    coequipiers: [],
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    ...overrides,
  };
}

function pause(overrides: Partial<PauseAnimateurView> = {}): PauseAnimateurView {
  return {
    date: '2026-07-10',
    debut: '18:40:00',
    fin: '19:00:00',
    heureLimite: '19:00:00',
    dureeMinutes: 20,
    standId: 'stand-1',
    standNom: 'Stand un',
    relaisDisponible: true,
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    ...overrides,
  };
}

describe('EspacePlanningPage — « Emporter mon planning »', () => {
  const espaceView = signal<EspaceAnimateurView | null>(view());
  const espaceJeton = signal<string | null>('jeton-1');
  const regenererAbonnement = vi.fn(async () => undefined);
  let fixture: ComponentFixture<EspacePlanningPage>;
  let writeText: ReturnType<typeof vi.fn>;

  function clipboard(resultat: 'accepte' | 'refuse'): void {
    writeText = vi.fn(async () => {
      if (resultat === 'refuse') {
        // What Chrome throws when the document no longer has focus.
        throw new DOMException('Document is not focused.', 'NotAllowedError');
      }
    });
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  }

  async function rendre(): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: EspaceAnimateurService,
          useValue: {
            view: espaceView,
            jeton: espaceJeton,
            regenererAbonnement,
            confirmerPlanning: vi.fn(async () => undefined),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(EspacePlanningPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Matched on the label span: the icon ligature is text inside the button too. */
  function bouton(libelle: string): HTMLButtonElement {
    return Array.from(racine().querySelectorAll('button')).find((each) =>
      Array.from(each.querySelectorAll('span')).some(
        (span) => span.textContent!.trim() === libelle,
      ),
    )!;
  }

  /** The address and its buttons are folded away until this is clicked. */
  async function deplier(): Promise<void> {
    bouton("Copier l'adresse, ou la remplacer").click();
    await fixture.whenStable();
  }

  async function copy(): Promise<void> {
    bouton("Copier l'adresse").click();
    await fixture.whenStable();
  }

  function succes(): Element | null {
    return racine().querySelector('.espace-abonnement-copie');
  }

  function erreur(): Element | null {
    return racine().querySelector('.espace-abonnement-erreur');
  }

  beforeEach(() => {
    // Outside the event, and deliberately so: this block's fixtures are dated
    // July 2026 and what it checks is the « Emporter mon planning » band, not
    // the day marker (#535). A real clock would make these assertions depend
    // on the date the suite happens to run on.
    // `toFake: ['Date']` and nothing else: faking the timers too would freeze
    // `whenStable()`, which waits for the application to settle.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 8, 1, 12, 0));
    espaceView.set(view());
    espaceJeton.set('jeton-1');
    regenererAbonnement.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('offers the subscription address on its own path and token', async () => {
    await rendre();
    await deplier();

    const url = racine().querySelector('.espace-abonnement-url')!.textContent!.trim();
    expect(url).toBe(`${window.location.origin}/api/abonnements/abo-1/planning.ics`);
    // The webcal scheme is what a calendar client registers for.
    const lien = racine().querySelector<HTMLAnchorElement>('.espace-agenda-abonnement')!;
    expect(lien.getAttribute('href')).toBe('webcal://' + url.replace(/^https?:\/\//, ''));
    // The espace token stays where it was: it never reaches this address.
    expect(url).not.toContain('jeton-1');
  });

  it('puts the band above the day cards, and the subscription first in it', async () => {
    espaceView.set(view({ postes: [poste()] }));
    await rendre();

    const bande = racine().querySelector('.espace-agenda')!;
    const premiereJournee = racine().querySelector('.espace-jour')!;
    // Node.DOCUMENT_POSITION_FOLLOWING: the card comes after the band.
    expect(bande.compareDocumentPosition(premiereJournee) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );

    // Reading order inside the band, and Material's own action hierarchy:
    // filled for the subscription, outlined for the PDF, plain text for the
    // one-shot ICS. Nothing invented, no hard-coded colour.
    const actions = Array.from(
      racine().querySelectorAll<HTMLAnchorElement>('.espace-agenda-actions a'),
    );
    // `span:not([class])` is the label: Material's own spans (ripple, touch
    // target) all carry a class, and the icon is a `mat-icon` element.
    expect(
      actions.map((each) => each.querySelector('span:not([class])')!.textContent!.trim()),
    ).toEqual(["S'abonner dans mon agenda", 'Télécharger en PDF', 'Télécharger le fichier ICS']);
    expect(actions[0].classList).toContain('mat-mdc-unelevated-button');
    expect(actions[1].classList).toContain('mat-mdc-outlined-button');
    expect(actions[2].classList).toContain('mat-mdc-button');
  });

  it('keeps the address, its copy and its replacement folded away by default', async () => {
    await rendre();

    // Unfolded, they would push the planning below the fold on a phone —
    // trading the defect for its mirror image.
    expect(racine().querySelector('.espace-abonnement')).toBeNull();
    const declencheur = bouton("Copier l'adresse, ou la remplacer");
    expect(declencheur.getAttribute('aria-expanded')).toBe('false');
    expect(declencheur.getAttribute('aria-controls')).toBe('espace-abonnement-details');
    // The container the button points at exists even folded.
    expect(racine().querySelector('#espace-abonnement-details')).not.toBeNull();

    await deplier();

    expect(racine().querySelector('.espace-abonnement')).not.toBeNull();
    expect(bouton("Copier l'adresse, ou la remplacer").getAttribute('aria-expanded')).toBe('true');
  });

  it('drops a pending replacement when the panel is folded back', async () => {
    await rendre();
    await deplier();
    bouton('Cette adresse a fuité, la remplacer').click();
    await fixture.whenStable();
    expect(racine().querySelector('.espace-abonnement-revocation')).not.toBeNull();

    await deplier();
    await deplier();

    // Reopening must not land straight on a destructive confirmation.
    expect(racine().querySelector('.espace-abonnement-revocation')).toBeNull();
  });

  it('offers the subscription with nothing published, and the files only with one', async () => {
    espaceView.set(view({ publieLe: null, postes: [] }));
    await rendre();

    // Subscribing ahead of the publication is the good gesture: the feed fills
    // itself. A PDF or an ICS of an empty planning is a photograph of nothing.
    expect(racine().querySelector('.espace-agenda-abonnement')).not.toBeNull();
    expect(racine().querySelectorAll('.espace-agenda-actions a').length).toBe(1);

    espaceView.set(view({ postes: [poste()] }));
    await rendre();

    expect(racine().querySelectorAll('.espace-agenda-actions a').length).toBe(3);
  });

  it('hides the whole block while the espace is not loaded yet', async () => {
    espaceView.set(null);
    await rendre();

    expect(racine().querySelector('.espace-abonnement')).toBeNull();
  });

  it('says the address is copied, and nothing else', async () => {
    clipboard('accepte');
    await rendre();
    await deplier();

    await copy();

    expect(writeText).toHaveBeenCalledExactlyOnceWith(
      `${window.location.origin}/api/abonnements/abo-1/planning.ics`,
    );
    expect(succes()).not.toBeNull();
    expect(erreur()).toBeNull();
  });

  it('drops the success message when a later copy is refused', async () => {
    clipboard('accepte');
    await rendre();
    await deplier();
    await copy();
    expect(succes()).not.toBeNull();

    clipboard('refuse');
    await copy();

    // The fallback the error asks for is « select the address above »; a
    // leftover « Adresse copiée » would tell the animateur not to bother.
    expect(succes()).toBeNull();
    expect(erreur()!.textContent).toContain('Copie impossible');
  });

  it('drops the error message when a later copy succeeds', async () => {
    clipboard('refuse');
    await rendre();
    await deplier();
    await copy();
    expect(erreur()).not.toBeNull();

    clipboard('accepte');
    await copy();

    expect(erreur()).toBeNull();
    expect(succes()).not.toBeNull();
  });

  it('prints the break of the day under its title, and says when nobody can relay', async () => {
    espaceView.set(
      view({
        postes: [poste()],
        pauses: [pause()],
      }),
    );
    await rendre();

    const note = (fixture.nativeElement as HTMLElement).querySelector('.espace-pause')!;
    expect(note.textContent).toContain('Pause de 18:40 à 19:00, sur Stand un');
    expect(note.querySelector('.espace-pause-seul')).toBeNull();

    espaceView.set(
      view({
        postes: [poste()],
        pauses: [pause({ relaisDisponible: false })],
      }),
    );
    await rendre();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.espace-pause-seul')?.textContent,
    ).toContain("personne d'autre sur le stand");
  });
});

/*
 * The rest of the page: what changed (#532), where (#534) and the day marker
 * (#535). A block of its own because the clock is the subject here, where the
 * one above only freezes it so as not to depend on it.
 */
describe("EspacePlanningPage — la page pendant l'événement", () => {
  const espaceView = signal<EspaceAnimateurView | null>(view());
  let fixture: ComponentFixture<EspacePlanningPage>;

  async function rendre(quand: Date, vue: EspaceAnimateurView): Promise<void> {
    // Only `Date` is faked: freezing the timers would freeze `whenStable()`.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(quand);
    espaceView.set(vue);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: EspaceAnimateurService,
          useValue: {
            view: espaceView,
            jeton: signal<string | null>('jeton-1'),
            regenererAbonnement: vi.fn(async () => undefined),
            confirmerPlanning: vi.fn(async () => undefined),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(EspacePlanningPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => {
    vi.useRealTimers();
  });

  /* ---------------- What changed for me (#532) ---------------- */

  it('affiche les phrases stockées de la dernière publication, dans leur ordre', async () => {
    await rendre(
      new Date(2026, 6, 11, 9, 0),
      view({
        postes: [poste()],
        changements: [
          'samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h',
          'dimanche 12/07 : Kubb 10h-12h (nouveau)',
        ],
        changementsLe: '2026-07-05T08:30:00Z',
      }),
    );

    const bandeau = racine().querySelector('.espace-changements')!;
    expect(
      Array.from(bandeau.querySelectorAll('li')).map((each) => each.textContent!.trim()),
    ).toEqual([
      'samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h',
      'dimanche 12/07 : Kubb 10h-12h (nouveau)',
    ]);
    // Above the day cards: it is what the button just below asks to confirm,
    // and nobody can be asked to confirm what they were never shown.
    const firstDayCard = racine().querySelector('.espace-jour')!;
    expect(bandeau.compareDocumentPosition(firstDayCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it("ne montre aucun bandeau quand le serveur n'envoie rien", async () => {
    // Never published, or a first delivery: the server answers an empty list
    // — a first delivery is not a list of corrections.
    await rendre(new Date(2026, 6, 11, 9, 0), view({ postes: [poste()], changements: [] }));

    expect(racine().querySelector('.espace-changements')).toBeNull();
  });

  it('fait passer le bandeau en second plan une fois la présence confirmée', async () => {
    const changements = ['samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h'];
    await rendre(new Date(2026, 6, 11, 9, 0), view({ postes: [poste()], changements }));
    expect(racine().querySelector('.espace-changements')!.classList).not.toContain(
      'espace-changements-actes',
    );

    await rendre(
      new Date(2026, 6, 11, 9, 0),
      view({
        postes: [poste()],
        changements,
        statutConfirmation: 'CONFIRME',
        confirmeLe: '2026-07-06T09:00:00Z',
      }),
    );

    // Still readable — what changed does not become false — but an alert that
    // stays after being answered stops alerting about anything.
    const bandeau = racine().querySelector('.espace-changements')!;
    expect(bandeau.classList).toContain('espace-changements-actes');
    expect(bandeau.querySelectorAll('li')).toHaveLength(1);
  });

  /* ---------------------- The place (#534) ---------------------- */

  it("nomme l'emplacement du stand et l'ouvre sur une carte quand il est géocodé", async () => {
    await rendre(
      new Date(2026, 6, 11, 9, 0),
      view({
        postes: [
          poste({
            emplacementNom: 'Hall B',
            emplacementLatitude: 47.2184,
            emplacementLongitude: -1.5536,
          }),
        ],
      }),
    );

    const lien = racine().querySelector<HTMLAnchorElement>('.espace-poste-lieu a')!;
    expect(lien.textContent!.trim()).toBe('Hall B');
    expect(lien.getAttribute('href')).toBe(
      'https://www.openstreetmap.org/?mlat=47.2184&mlon=-1.5536#map=18/47.2184/-1.5536',
    );
    // The espace token lives in this page's URL: it must not leave in the map
    // provider's `Referer`.
    expect(lien.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it("écrit l'emplacement sans lien quand il n'a pas de coordonnées", async () => {
    await rendre(
      new Date(2026, 6, 11, 9, 0),
      view({ postes: [poste({ emplacementNom: 'Chapiteau' })] }),
    );

    const lieu = racine().querySelector('.espace-poste-lieu')!;
    expect(lieu.textContent).toContain('Chapiteau');
    expect(lieu.querySelector('a')).toBeNull();
  });

  it("n'ajoute rien du tout à un stand sans emplacement", async () => {
    await rendre(new Date(2026, 6, 11, 9, 0), view({ postes: [poste()] }));

    expect(racine().querySelector('.espace-poste-lieu')).toBeNull();
  });

  it('porte aussi le lieu sur la pause : sortir suppose de savoir où revenir', async () => {
    await rendre(
      new Date(2026, 6, 11, 9, 0),
      view({
        postes: [poste()],
        pauses: [
          pause({
            emplacementNom: 'Hall B',
            emplacementLatitude: 47.2,
            emplacementLongitude: -1.5,
          }),
        ],
      }),
    );

    expect(racine().querySelector('.espace-pause .espace-poste-lieu a')!.textContent!.trim()).toBe(
      'Hall B',
    );
  });

  /* ------------------- The « now » marker (#535) ------------------- */

  it('met le poste en cours en tête, avec son lieu', async () => {
    await rendre(
      new Date(2026, 6, 10, 11, 0),
      // `HH:mm:ss` is what the API answers, seconds included.
      view({
        postes: [poste({ heureDebut: '10:00:00', heureFin: '12:00:00', emplacementNom: 'Hall B' })],
      }),
    );

    const tete = racine().querySelector('.espace-maintenant')!;
    // Read at a glance: the seconds the server sends have no place here, nor
    // on the day card below.
    expect(tete.textContent).toContain('En ce moment : Stand un, 10:00–12:00');
    expect(tete.querySelector('.espace-maintenant-lieu')!.textContent).toContain('Hall B');
    expect(racine().querySelector('.espace-poste-horaire')!.textContent).toBe('10:00–12:00');
  });

  it("annonce le prochain poste quand aucun n'est en cours", async () => {
    await rendre(
      new Date(2026, 6, 10, 8, 0),
      view({ postes: [poste(), poste({ creneauId: 2, date: '2026-07-12', standNom: 'Kubb' })] }),
    );

    expect(racine().querySelector('.espace-maintenant')!.textContent).toContain(
      'Prochain poste : Stand un',
    );
  });

  it('garde la journée de repos explicite, y compris aujourd’hui', async () => {
    await rendre(
      new Date(2026, 6, 11, 11, 0),
      view({ postes: [poste()], joursRepos: ['2026-07-11'] }),
    );

    expect(racine().querySelector('.espace-maintenant')!.textContent).toContain('Repos aujourd');
    // The « Repos » card stays: it is an answer, not a hole.
    const cartes = Array.from(racine().querySelectorAll('.espace-jour'));
    expect(cartes.some((carte) => carte.querySelector('.espace-repos'))).toBe(true);
  });

  it('replie les journées écoulées derrière un seul bouton, sans les supprimer', async () => {
    await rendre(
      new Date(2026, 6, 12, 11, 0),
      view({
        postes: [
          poste(),
          poste({ creneauId: 2, date: '2026-07-11', standNom: 'Molkky' }),
          poste({ creneauId: 3, date: '2026-07-12', standNom: 'Kubb' }),
        ],
      }),
    );

    // Today first, the two elapsed days behind the button.
    const cartes = () => Array.from(racine().querySelectorAll('.espace-jour'));
    expect(cartes()).toHaveLength(1);
    expect(cartes()[0].classList).toContain('espace-jour-aujourdhui');
    const declencheur = racine().querySelector<HTMLButtonElement>('.espace-jours-passes')!;
    expect(declencheur.textContent).toContain('Voir les journées passées (2)');
    expect(declencheur.getAttribute('aria-expanded')).toBe('false');

    declencheur.click();
    await fixture.whenStable();

    // Folded, never removed: a planning that stops short reads as a bug.
    expect(cartes()).toHaveLength(3);
    expect(cartes()[0].classList).toContain('espace-jour-passe');
    expect(racine().querySelector('.espace-jours-passes')!.getAttribute('aria-expanded')).toBe(
      'true',
    );
  });

  it("garde ouverte la journée d'un poste de nuit encore tenu après minuit", async () => {
    await rendre(
      // 00:30 on the 12th: the « 22h–02h » seat is dated the 11th and the
      // person is on it. Folding the 11th away would take off their screen the
      // very day they are working.
      new Date(2026, 6, 12, 0, 30),
      view({
        postes: [
          poste({ date: '2026-07-11', heureDebut: '22:00:00', heureFin: '02:00:00' }),
          poste({ creneauId: 2, date: '2026-07-12', standNom: 'Kubb' }),
        ],
      }),
    );

    expect(racine().querySelector('.espace-maintenant')!.textContent).toContain(
      'En ce moment : Stand un',
    );
    expect(racine().querySelector('.espace-jours-passes')).toBeNull();
    const cartes = Array.from(racine().querySelectorAll('.espace-jour'));
    expect(cartes).toHaveLength(2);
    expect(cartes[0].classList).not.toContain('espace-jour-passe');
    // « Aujourd'hui » marks the day's card, not yesterday's.
    expect(cartes[1].classList).toContain('espace-jour-aujourdhui');
  });

  it('se place sur la date figée du serveur plutôt que sur celle du téléphone', async () => {
    // The phone says September, long after the event: without the frozen
    // date, the page would go back to its plain form.
    await rendre(
      new Date(2026, 8, 14, 11, 0),
      view({
        dateDuJourFigee: '2026-07-12',
        postes: [
          poste({ date: '2026-07-10', standNom: 'Molkky' }),
          poste({
            creneauId: 2,
            date: '2026-07-12',
            standNom: 'Kubb',
            heureDebut: '14:00',
            heureFin: '16:00',
          }),
        ],
      }),
    );

    expect(racine().querySelector('.espace-maintenant')!.textContent).toContain(
      'Prochain poste : Kubb',
    );
    expect(racine().querySelector('.espace-jours-passes')).not.toBeNull();
    expect(racine().querySelector('.espace-jour')!.classList).toContain('espace-jour-aujourdhui');
  });

  it('ne replie rien et ne dit rien hors événement', async () => {
    await rendre(
      new Date(2026, 8, 1, 12, 0),
      view({ postes: [poste(), poste({ creneauId: 2, date: '2026-07-12', standNom: 'Kubb' })] }),
    );

    // Before the first day, after the last: the page goes back to its plain
    // form rather than carrying a misleading head block.
    expect(racine().querySelector('.espace-maintenant')).toBeNull();
    expect(racine().querySelector('.espace-jours-passes')).toBeNull();
    expect(racine().querySelectorAll('.espace-jour')).toHaveLength(2);
  });

  it('garde la journée du jour dépliée même quand le fuseau la place un autre jour en UTC', async () => {
    vi.stubEnv('TZ', 'Pacific/Kiritimati');
    try {
      // 00:30 there on the 11th is the 10th in UTC. Reading the clock in UTC
      // would fold away the day the person is living.
      const minuitPasse = new Date(2026, 6, 11, 0, 30);
      expect(minuitPasse.toISOString().slice(0, 10)).toBe('2026-07-10');

      await rendre(
        minuitPasse,
        view({
          postes: [
            poste({ date: '2026-07-11', standNom: 'Molkky' }),
            poste({ creneauId: 2, date: '2026-07-12', standNom: 'Kubb' }),
          ],
        }),
      );

      expect(racine().querySelector('.espace-jour')!.classList).toContain('espace-jour-aujourdhui');
      expect(racine().querySelector('.espace-jours-passes')).toBeNull();
    } finally {
      vi.unstubAllEnvs();
    }
  });
});
