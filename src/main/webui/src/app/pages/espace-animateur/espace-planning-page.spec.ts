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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { EspaceAnimateurView, PosteAnimateurView } from '../../core/models';
import { EspacePlanningPage } from './espace-planning-page';

function vue(overrides: Partial<EspaceAnimateurView> = {}): EspaceAnimateurView {
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
    ...overrides
  } as EspaceAnimateurView;
}

function poste(): PosteAnimateurView {
  return {
    date: '2026-07-10',
    standId: 'stand-1',
    standNom: 'Stand un',
    creneauId: 1,
    heureDebut: '10:00',
    heureFin: '12:00',
    coequipiers: []
  };
}

describe('EspacePlanningPage — « Emporter mon planning »', () => {
  const espaceVue = signal<EspaceAnimateurView | null>(vue());
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
            vue: espaceVue,
            jeton: espaceJeton,
            regenererAbonnement,
            confirmerPlanning: vi.fn(async () => undefined)
          }
        }
      ]
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
      Array.from(each.querySelectorAll('span')).some((span) => span.textContent!.trim() === libelle)
    )!;
  }

  /** The address and its buttons are folded away until this is clicked. */
  async function deplier(): Promise<void> {
    bouton("Copier l'adresse, ou la remplacer").click();
    await fixture.whenStable();
  }

  async function copier(): Promise<void> {
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
    espaceVue.set(vue());
    espaceJeton.set('jeton-1');
    regenererAbonnement.mockClear();
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
    espaceVue.set(vue({ postes: [poste()] }));
    await rendre();

    const bande = racine().querySelector('.espace-agenda')!;
    const premiereJournee = racine().querySelector('.espace-jour')!;
    // Node.DOCUMENT_POSITION_FOLLOWING: the card comes after the band.
    expect(bande.compareDocumentPosition(premiereJournee) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING
    );

    // Reading order inside the band, and Material's own action hierarchy:
    // filled for the subscription, outlined for the PDF, plain text for the
    // one-shot ICS. Nothing invented, no hard-coded colour.
    const actions = Array.from(
      racine().querySelectorAll<HTMLAnchorElement>('.espace-agenda-actions a')
    );
    // `span:not([class])` is the label: Material's own spans (ripple, touch
    // target) all carry a class, and the icon is a `mat-icon` element.
    expect(
      actions.map((each) => each.querySelector('span:not([class])')!.textContent!.trim())
    ).toEqual([
      "S'abonner dans mon agenda",
      'Télécharger en PDF',
      'Télécharger le fichier ICS'
    ]);
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
    espaceVue.set(vue({ publieLe: null, postes: [] }));
    await rendre();

    // Subscribing ahead of the publication is the good gesture: the feed fills
    // itself. A PDF or an ICS of an empty planning is a photograph of nothing.
    expect(racine().querySelector('.espace-agenda-abonnement')).not.toBeNull();
    expect(racine().querySelectorAll('.espace-agenda-actions a').length).toBe(1);

    espaceVue.set(vue({ postes: [poste()] }));
    await rendre();

    expect(racine().querySelectorAll('.espace-agenda-actions a').length).toBe(3);
  });

  it('hides the whole block while the espace is not loaded yet', async () => {
    espaceVue.set(null);
    await rendre();

    expect(racine().querySelector('.espace-abonnement')).toBeNull();
  });

  it('says the address is copied, and nothing else', async () => {
    clipboard('accepte');
    await rendre();
    await deplier();

    await copier();

    expect(writeText).toHaveBeenCalledExactlyOnceWith(
      `${window.location.origin}/api/abonnements/abo-1/planning.ics`
    );
    expect(succes()).not.toBeNull();
    expect(erreur()).toBeNull();
  });

  it('drops the success message when a later copy is refused', async () => {
    clipboard('accepte');
    await rendre();
    await deplier();
    await copier();
    expect(succes()).not.toBeNull();

    clipboard('refuse');
    await copier();

    // The fallback the error asks for is « select the address above »; a
    // leftover « Adresse copiée » would tell the animateur not to bother.
    expect(succes()).toBeNull();
    expect(erreur()!.textContent).toContain('Copie impossible');
  });

  it('drops the error message when a later copy succeeds', async () => {
    clipboard('refuse');
    await rendre();
    await deplier();
    await copier();
    expect(erreur()).not.toBeNull();

    clipboard('accepte');
    await copier();

    expect(erreur()).toBeNull();
    expect(succes()).not.toBeNull();
  });
});
