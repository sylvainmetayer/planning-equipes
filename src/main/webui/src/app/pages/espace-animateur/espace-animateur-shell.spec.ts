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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { APP_CONFIG } from '../../core/app-config';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { AppConfig, EspaceAnimateurView } from '../../core/models';
import { EspaceAnimateurShell } from './espace-animateur-shell';

// The dates are read in French, as `main.ts` serves them: the band is written
// with the DatePipe, so the month names are the locale's and not the code's.
registerLocaleData(localeFr, 'fr');

const CONFIG: AppConfig = {
  sentryDsn: '',
  sentryEnvironment: 'local',
  cloudflareWebAnalyticsToken: '',
  devMode: false,
  dragDropEnabled: false,
  version: '',
  authOidc: true,
  authSecours: false,
};

function view(overrides: Partial<EspaceAnimateurView> = {}): EspaceAnimateurView {
  return {
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
          },
        },
        { provide: APP_CONFIG, useValue: CONFIG },
        {
          provide: AdminApi,
          useValue: { session: vi.fn(), logout: vi.fn(), oidcLoginUrl: vi.fn() },
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
});

/**
 * The access screen a 401 renders: the e-mail code is gone, a Keycloak
 * session is the only key, and the screen must never loop on a sign-in the
 * person has already done.
 */
describe("EspaceAnimateurShell — écran d'accès", () => {
  let fixture: ComponentFixture<EspaceAnimateurShell>;
  const adminApi = {
    session: vi.fn(),
    logout: vi.fn(),
    oidcLoginUrl: vi.fn((retour: string) => `/api/auth/oidc/login?redirect=${retour}`),
  };

  async function rendre(options: { authOidc: boolean; authentifie?: boolean }): Promise<void> {
    adminApi.session.mockReset();
    adminApi.session.mockResolvedValue({
      authentifie: options.authentifie ?? false,
      nom: options.authentifie ? 'marie' : null,
      roles: options.authentifie ? ['user'] : [],
    });
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
          },
        },
        { provide: APP_CONFIG, useValue: { ...CONFIG, authOidc: options.authOidc } },
        { provide: AdminApi, useValue: adminApi },
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
    // The session probe runs after `charger` settles: let both resolve.
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function textOf(): string {
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
      b.textContent!.includes(libelle),
    );
  }

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('propose de se connecter avec son compte, et revient sur ce même espace', async () => {
    const assign = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
    await rendre({ authOidc: true });

    expect(textOf()).not.toContain('code');
    bouton('Se connecter avec mon compte')!.click();

    expect(adminApi.oidcLoginUrl).toHaveBeenCalledWith('/animateur/jeton-1');
    expect(assign).toHaveBeenCalledExactlyOnceWith(
      '/api/auth/oidc/login?redirect=/animateur/jeton-1',
    );
  });

  it("déjà connecté avec un compte qui n'ouvre pas l'espace : le dit et offre la déconnexion", async () => {
    await rendre({ authOidc: true, authentifie: true });

    expect(textOf()).toContain("ce compte n'ouvre pas cet espace");
    expect(bouton('Se connecter avec mon compte')).toBeUndefined();
    expect(bouton('Se déconnecter')).toBeDefined();
  });

  // RGAA 12.7: a skip link to a focusable <main>, as in the admin shell.
  it('moves the focus to <main> from the skip link', async () => {
    await rendre({ authOidc: true });
    const root = fixture.nativeElement as HTMLElement;
    const skip = root.querySelector<HTMLAnchorElement>('a.skip-link')!;
    const main = root.querySelector<HTMLElement>('main#contenu')!;
    document.body.appendChild(root);

    expect(skip).not.toBeNull();
    expect(main.getAttribute('tabindex')).toBe('-1');
    skip.click();
    expect(document.activeElement).toBe(main);
    root.remove();
  });

  it("sans Keycloak, l'espace se dit indisponible et n'offre aucune porte", async () => {
    await rendre({ authOidc: false });

    expect(textOf()).toContain('momentanément indisponible');
    expect(bouton('Se connecter')).toBeUndefined();
    expect(adminApi.session).not.toHaveBeenCalled();
  });
});
