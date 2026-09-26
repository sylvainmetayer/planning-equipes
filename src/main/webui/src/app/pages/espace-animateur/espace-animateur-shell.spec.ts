// Which édition an espace page is about (issue #608).
//
// An animateur back from one year to the next holds two espace links, both
// valid for ever, and the two pages were rigorously identical: « votre
// planning », no event name, no dates. Reading the wrong one shows a
// perfectly coherent planning that is simply not this year's, and nothing
// warns them. Rendered rather than driven through the class, because the
// defect is exactly what the page does and does not say.

import { LOCALE_ID, provideZonelessChangeDetection, signal } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { EspaceAnimateurView } from '../../core/models';
import { EspaceAnimateurShell } from './espace-animateur-shell';

// The dates are read in French, as `main.ts` serves them: the band is written
// with the DatePipe, so the month names are the locale's and not the code's.
registerLocaleData(localeFr, 'fr');

function view(overrides: Partial<EspaceAnimateurView> = {}): EspaceAnimateurView {
  return {
    signalements: [],
    collecteOuverte: false,
    collecteFermeLe: null,
    dernierEnvoi: null,
    joursRepos: [],
    animateurId: 'alice',
    prenom: 'Alice',
    nom: 'Martin',
    publieLe: '2026-02-01T10:00:00Z',
    foireOuverte: true,
    postes: [],
    collegues: [],
    statutConfirmation: 'NON_VU',
    confirmeLe: null,
    foireOuvreLe: null,
    foireFermeLe: null,
    abonnementToken: 'abo-1',
    consignes: [],
    pauses: [],
    dateDuJourFigee: null,
    heureDuJourFigee: null,
    editionNom: 'Festival 26',
    editionDebut: '2026-02-01',
    editionFin: '2026-02-16',
    ...overrides,
  } as EspaceAnimateurView;
}

describe('EspaceAnimateurShell — quelle édition', () => {
  const espaceView = signal<EspaceAnimateurView | null>(view());
  let fixture: ComponentFixture<EspaceAnimateurShell>;

  async function rendre(vue: EspaceAnimateurView | null): Promise<void> {
    espaceView.set(vue);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: LOCALE_ID, useValue: 'fr' },
        {
          provide: EspaceAnimateurService,
          useValue: {
            view: espaceView,
            jeton: signal('jeton-1'),
            chargement: signal(false),
            erreur: signal(null),
            authRequise: signal(false),
            charger: vi.fn(async () => undefined),
            demanderCode: vi.fn(),
            validerCode: vi.fn(),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ jeton: 'jeton-1' }) },
            paramMap: of(convertToParamMap({ jeton: 'jeton-1' })),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(EspaceAnimateurShell);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function textOf(): string {
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  beforeEach(async () => {
    await rendre(view());
  });

  it("nomme l'édition et ses bornes au-dessus des onglets", () => {
    expect(textOf()).toContain('Festival 26');
    expect(textOf()).toContain('du 1 février au 16 février 2026');
  });

  /** Two éditions, two pages: this line is what tells them apart. */
  it('affiche une autre étiquette pour une autre édition', async () => {
    await rendre(
      view({ editionNom: 'Festival 25', editionDebut: '2025-02-02', editionFin: '2025-02-17' }),
    );

    expect(textOf()).toContain('Festival 25');
    expect(textOf()).toContain('2025');
    expect(textOf()).not.toContain('Festival 26');
  });

  /** A one-day édition does not read « du 14 au 14 ». */
  it("dit « le » quand l'événement tient sur une journée", async () => {
    await rendre(view({ editionDebut: '2026-02-14', editionFin: '2026-02-14' }));

    expect(textOf()).toContain('le 14 février 2026');
    expect(textOf()).not.toContain('du 14');
  });

  /**
   * The bounds are derived from the créneaux (docs/domaine.md): an édition
   * holding none has no bounds, and its name is then the whole label.
   */
  it("garde le nom quand l'édition n'a pas encore de créneau", async () => {
    await rendre(view({ editionDebut: null, editionFin: null }));

    expect(textOf()).toContain('Festival 26');
    expect(textOf()).not.toContain('février');
  });

  /** An édition the server cannot read leaves the page as it was. */
  it("n'affiche pas de bandeau vide quand le serveur ne sait pas nommer l'édition", async () => {
    await rendre(view({ editionNom: null, editionDebut: null, editionFin: null }));

    expect((fixture.nativeElement as HTMLElement).querySelector('.espace-edition')).toBeNull();
  });

  function liensNav(): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('nav.espace-nav a'),
    ).map((lien) =>
      (lien.textContent ?? '')
        .replace(lien.querySelector('mat-icon')?.textContent ?? '', '')
        .trim(),
    );
  }

  /** Out of the collection, two dead tabs were read all event long: they are gone. */
  it('offers three tabs outside the collection, and the two collection pages only while it is open', async () => {
    expect(liensNav()).toEqual(['Mon planning', 'Mes échanges', 'Aide']);
    expect(textOf()).not.toContain('La collecte des disponibilités est ouverte');

    await rendre(view({ collecteOuverte: true, collecteFermeLe: '2026-01-20' }));

    expect(liensNav()).toHaveLength(5);
    expect(textOf()).toContain("La collecte des disponibilités est ouverte jusqu'au 20 janvier");
  });

  /** Who to call, at the foot of every page — and nothing at all when the edition gave no contact. */
  it("shows the organisation's contact at the foot of the page when there is one", async () => {
    expect((fixture.nativeElement as HTMLElement).querySelector('.espace-contact')).toBeNull();

    await rendre({
      ...view(),
      contact: { telephone: '04 00 00 00 00', email: null },
    } as EspaceAnimateurView);

    const contact = (fixture.nativeElement as HTMLElement).querySelector('.espace-contact-pied')!;
    expect(contact.textContent).toContain('Organisation');
    expect(contact.querySelector('a[href="tel:04 00 00 00 00"]')).not.toBeNull();
  });
});

// The accessibility groundwork the admin shell had and the espace never
// received (RGAA 12.7 and 7.5): a skip link to a focusable <main>, and an
// access-code failure that is announced instead of silently printed.
describe('EspaceAnimateurShell — accessibility groundwork', () => {
  let fixture: ComponentFixture<EspaceAnimateurShell>;
  const validateCode = vi.fn();

  beforeEach(async () => {
    validateCode.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: LOCALE_ID, useValue: 'fr' },
        {
          provide: EspaceAnimateurService,
          useValue: {
            view: signal(null),
            jeton: signal('jeton-1'),
            chargement: signal(false),
            erreur: signal(null),
            authRequise: signal(true),
            charger: vi.fn(async () => undefined),
            demanderCode: vi.fn(async () => 'a***@example.org'),
            validerCode: validateCode,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ jeton: 'jeton-1' }) },
            paramMap: of(convertToParamMap({ jeton: 'jeton-1' })),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(EspaceAnimateurShell);
    await fixture.whenStable();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('moves the focus to <main> from the skip link', () => {
    const skip = root().querySelector<HTMLAnchorElement>('a.skip-link')!;
    const main = root().querySelector<HTMLElement>('main#contenu')!;
    document.body.appendChild(root());

    expect(skip).not.toBeNull();
    expect(main.getAttribute('tabindex')).toBe('-1');
    skip.click();
    expect(document.activeElement).toBe(main);
    root().remove();
  });

  it('announces a wrong access code as an alert', async () => {
    validateCode.mockRejectedValue(new Error('Code incorrect'));
    (root().querySelector('.espace-code button[matButton="filled"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    const input = root().querySelector<HTMLInputElement>('input[name="code"]')!;
    input.value = '000000';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    root()
      .querySelector('form.espace-code-form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    const alert = root().querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert!.textContent).toContain('Code incorrect');
  });
});
