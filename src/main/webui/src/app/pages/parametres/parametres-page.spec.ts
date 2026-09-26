// The rendered page — this screen holds the most destructive button of the
// application (replay a SQL dump, every edition included). The ninja picker and
// the end-of-solve mail moved to « Règles du planning › Calcul » and are tested
// in `regles-calcul.spec.ts`.
//
// The scenario operations left this screen: they are covered by
// `scenario-preenregistre.spec.ts` (Débogage), `import-scenario-card.spec.ts`
// (Imports) and `exports-page.spec.ts`.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AdminApi } from '../../core/api/admin-api';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { EchangesApi } from '../../core/api/echanges-api';
import { EditionsApi } from '../../core/api/editions-api';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { REPLACE_KEYWORD, ParametresPage } from './parametres-page';
import type { DemandeRecopie } from '../../shared/confirmation-recopie';
import type { EtatSauvegarde } from '../../core/models';

function text(element: HTMLElement): string {
  return element.textContent!.replace(/\s+/g, ' ').trim();
}

/** A `File` jsdom can read: its own implementation has no `text()`. */
function file(nom: string, contenu: string): File {
  const created = new File([contenu], nom);
  Object.defineProperty(created, 'text', { value: async () => contenu });
  return created;
}

describe('ParametresPage rendering', () => {
  let fixture: ComponentFixture<ParametresPage>;
  let api: { get: ReturnType<typeof vi.fn> };
  let adminApi: {
    mailConfig: ReturnType<typeof vi.fn>;
    backups: ReturnType<typeof vi.fn>;
    setBackupsActive: ReturnType<typeof vi.fn>;
    exportDatabase: ReturnType<typeof vi.fn>;
    importDatabase: ReturnType<typeof vi.fn>;
    notificationSettings: ReturnType<typeof vi.fn>;
    organisationContact: ReturnType<typeof vi.fn>;
    saveOrganisationContact: ReturnType<typeof vi.fn>;
  };
  let recopie: { demander: ReturnType<typeof vi.fn> };
  let instantane: { proposer: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let rename: ReturnType<typeof vi.fn>;
  let saveFoire: ReturnType<typeof vi.fn>;
  const editingLocked = signal(false);

  const SAUVEGARDE: EtatSauvegarde = {
    configured: true,
    directory: '/backups',
    active: true,
    retention: 10,
    cron: '0 0 4 * * ?',
    zone: 'Europe/Paris',
    nextRun: '2026-03-09T03:00:00Z',
    lastRun: {
      attemptedAt: '2026-03-08T03:00:00Z',
      succeeded: true,
      file: 'planning-20260308-040000.dump',
      message: null,
    },
    files: [
      {
        name: 'planning-20260308-040000.dump',
        sizeBytes: 5 * 1024 * 1024,
        createdAt: '2026-03-08T03:00:00Z',
      },
      {
        name: 'planning-20260307-040000.dump',
        sizeBytes: 5 * 1024 * 1024,
        createdAt: '2026-03-07T03:00:00Z',
      },
    ],
    directoryError: null,
    alertRecipientMissing: false,
  };

  async function rendre(
    options: {
      adminEmail?: string | null;
      sauvegarde?: EtatSauvegarde;
      /** Which tab to open; the page opens on « Édition » (issue #720). */
      onglet?: 'Instance' | 'Affichage mural';
    } = {},
  ): Promise<void> {
    editingLocked.set(false);
    notify = vi.fn();
    rename = vi.fn(async () => undefined);
    saveFoire = vi.fn(async (configuration: { foireOuverte: boolean }) => ({
      ...configuration,
      debut: null,
      fin: null,
      ouverteAujourdhui: configuration.foireOuverte,
    }));
    recopie = { demander: vi.fn(async () => true) };
    instantane = { proposer: vi.fn(async () => undefined) };
    api = { get: vi.fn(async () => []) };
    adminApi = {
      mailConfig: vi.fn(async () => ({
        adminEmail: options.adminEmail === undefined ? 'admin@exemple.test' : options.adminEmail,
      })),
      backups: vi.fn(async () => options.sauvegarde ?? SAUVEGARDE),
      setBackupsActive: vi.fn(async (active: boolean) => ({
        ...(options.sauvegarde ?? SAUVEGARDE),
        active,
      })),
      exportDatabase: vi.fn(async () => 'Téléchargement démarré.'),
      importDatabase: vi.fn(async () => ({ message: 'Base remplacée.' })),
      notificationSettings: vi.fn(async () => ({
        actives: false,
        heureRappelVeille: '18:00:00',
        delaiRelanceHeures: 72,
        ancienneteEchangeJours: 3,
      })),
      organisationContact: vi.fn(async () => ({ telephone: '01 23 45 67 89', email: null })),
      saveOrganisationContact: vi.fn(async (contact: unknown) => contact),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: AdminApi, useValue: adminApi },
        {
          provide: EditionStore,
          useValue: {
            courant: () => ({ id: 'E1', nom: 'Festival 2026' }),
            reload: vi.fn(async () => undefined),
          },
        },
        { provide: EditionsApi, useValue: { rename } },
        {
          provide: DisponibilitesApi,
          useValue: {
            configuration: vi.fn(async () => ({ collecteOuverte: false, debut: null, fin: null })),
            saveConfiguration: vi.fn(),
          },
        },
        {
          provide: EchangesApi,
          useValue: {
            configuration: vi.fn(async () => ({
              foireOuverte: false,
              debut: null,
              fin: null,
              ouverteAujourdhui: false,
            })),
            saveConfiguration: saveFoire,
          },
        },
        {
          provide: ProblemesStore,
          useValue: { report: () => null, reloadFeasibility: vi.fn(async () => undefined) },
        },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: {
            solverBusy: () => editingLocked(),
            editingLocked,
            activeJobDescription: () => 'Une résolution est en cours.',
          },
        },
        { provide: NotificationService, useValue: { notify } },
        { provide: PlanSnapshotStore, useValue: { capturer: vi.fn(async () => undefined) } },
        { provide: InstantaneAvantAction, useValue: instantane },
        { provide: ConfirmationRecopie, useValue: recopie },
        {
          provide: ScenarioImportService,
          useValue: {
            importer: vi.fn(async () => ({ status: 'imported', result: null })),
            rechargerApresImport: vi.fn(async () => undefined),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ParametresPage);
    await fixture.whenStable();
    if (options.onglet) {
      await openTab(options.onglet);
    }
  }

  /** Clicks a tab of the toggle group, as a reader does — the cards under it are then rendered. */
  async function openTab(nom: string): Promise<void> {
    const onglet = Array.from(racine().querySelectorAll('mat-button-toggle')).find((each) =>
      each.textContent!.includes(nom),
    );
    expect(onglet, `onglet « ${nom} » absent`).toBeDefined();
    (onglet as HTMLElement).querySelector('button')!.click();
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle),
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  /** One card of the page, found by its heading — several now carry a switch. */
  function carte(titre: string): HTMLElement {
    const trouve = Array.from(racine().querySelectorAll('mat-card')).find((each) =>
      each.textContent!.includes(titre),
    );
    expect(trouve, `carte « ${titre} » absente`).toBeDefined();
    return trouve as HTMLElement;
  }

  it('locks the SQL dump replay while a solve runs', async () => {
    await rendre({ onglet: 'Instance' });
    editingLocked.set(true);
    await fixture.whenStable();

    expect(bouton('Importer un dump SQL').disabled).toBe(true);
  });

  it('points to « Règles du planning » for what decides the plan', async () => {
    await rendre();

    const lien = racine().querySelector('a[href="/regles"]');
    expect(lien?.textContent).toContain('Règles du planning');
  });

  it('renames the edition from its own tab, once the name changed', async () => {
    await rendre();
    const champ = racine().querySelector('input[name="nomEdition"]') as HTMLInputElement;
    expect(champ.value).toBe('Festival 2026');

    champ.value = 'Festival 2027';
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    carte("Nom de l'édition").querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(rename).toHaveBeenCalledExactlyOnceWith('E1', 'Festival 2027');
  });

  /** Échanges keeps one line of the foire's state: its switch and its dates live here. */
  it('opens the foire from the guichets, with its dates', async () => {
    await rendre();
    const guichets = carte('Guichets');
    const foire = guichets.querySelector('section[aria-labelledby="guichet-foire"]') as HTMLElement;
    (foire.querySelector('mat-slide-toggle button[role="switch"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    (
      Array.from(foire.querySelectorAll('button')).find((each) =>
        each.textContent!.includes('Enregistrer'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(saveFoire).toHaveBeenCalledExactlyOnceWith({
      foireOuverte: true,
      debut: null,
      fin: null,
    });
  });

  it("shows the organisation's contact the espace will display", async () => {
    await rendre();

    const telephone = carte("Contact de l'organisation").querySelector(
      'input[name="telephone"]',
    ) as HTMLInputElement;
    expect(telephone.value).toBe('01 23 45 67 89');
  });

  /** Picks the dump file the SQL card's hidden input reacts to. */
  function choisirDump(): void {
    const input = racine().querySelector('input[type="file"][accept^=".sql"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [file('sauvegarde.sql', '-- dump')],
    });
    input.dispatchEvent(new Event('change'));
  }

  it('never replays a SQL dump without a typed confirmation and a snapshot offer', async () => {
    await rendre({ onglet: 'Instance' });
    recopie.demander.mockResolvedValue(false);

    choisirDump();
    await fixture.whenStable();

    // Refused — a wrong entry and a cancellation both land here: it replaces
    // the whole database, every edition included.
    expect(adminApi.importDatabase).not.toHaveBeenCalled();
    expect(instantane.proposer).not.toHaveBeenCalled();

    recopie.demander.mockResolvedValue(true);
    choisirDump();
    await fixture.whenStable();

    expect(instantane.proposer).toHaveBeenCalledOnce();
    expect(adminApi.importDatabase).toHaveBeenCalledExactlyOnceWith('-- dump');
    expect(racine().textContent!).toContain('Base remplacée.');
  });

  // A dump is not scoped to an edition, so it must not ask for an edition
  // name: that would describe an operation narrower than the one it runs.
  it('asks for a keyword and says every edition is overwritten', async () => {
    await rendre({ onglet: 'Instance' });

    choisirDump();
    await fixture.whenStable();

    const demande = recopie.demander.mock.calls[0][0] as DemandeRecopie;
    expect(demande.valeurAttendue).toBe(REPLACE_KEYWORD);
    expect(demande.message).toContain('toutes les éditions sont écrasées');
    expect(demande.message).toContain('sauvegarde.sql');
  });

  it('exports the database as a file named after the deployment', async () => {
    await rendre({ onglet: 'Instance' });

    bouton('Exporter le dump SQL').click();
    await fixture.whenStable();

    expect(adminApi.exportDatabase).toHaveBeenCalledOnce();
    const [filename] = adminApi.exportDatabase.mock.calls[0] as unknown as [string];
    expect(filename.endsWith('.sql')).toBe(true);
  });

  // The automatic backup. What an administrator comes to this card for is one
  // question — "is the database still being backed up?" — so what it must
  // never do is answer it by silence: a missing directory, a failed night and
  // a dead deployment all look the same on a card that only shows a switch.

  it('shows where the dumps go, how many are kept and which ones are there', async () => {
    await rendre({ onglet: 'Instance' });

    const contenu = text(carte('Sauvegarde automatique'));
    expect(contenu).toContain('/backups');
    expect(contenu).toContain('10 sauvegardes');
    expect(contenu).toContain('planning-20260308-040000.dump');
    expect(contenu).toContain('5.0 Mo');
  });

  it('reports the last failed night instead of looking idle', async () => {
    await rendre({
      onglet: 'Instance',
      sauvegarde: {
        ...SAUVEGARDE,
        lastRun: {
          attemptedAt: '2026-03-08T03:00:00Z',
          succeeded: false,
          file: null,
          message: 'pg_dump failed (exit 1): connection refused',
        },
      },
    });

    expect(text(carte('Sauvegarde automatique'))).toContain('connection refused');
  });

  it('warns that a failed night alerts nobody when no admin address is configured', async () => {
    await rendre({
      onglet: 'Instance',
      sauvegarde: { ...SAUVEGARDE, alertRecipientMissing: true },
    });
    expect(text(carte('Sauvegarde automatique'))).toContain('MAIL_ADMIN');
  });

  it('says nothing about alerts when an admin address will receive them', async () => {
    await rendre({ onglet: 'Instance' });
    expect(text(carte('Sauvegarde automatique'))).not.toContain('MAIL_ADMIN');
  });

  it('says the feature is inert when the deployment configured no directory', async () => {
    await rendre({
      onglet: 'Instance',
      sauvegarde: { ...SAUVEGARDE, configured: false, directory: null, files: [], nextRun: null },
    });

    const contenu = text(carte('Sauvegarde automatique'));
    expect(contenu).toContain('BACKUP_DIR');
    // No switch to flip: turning one on would promise a backup nothing writes.
    expect(carte('Sauvegarde automatique').querySelector('mat-slide-toggle')).toBeNull();
  });

  it('suspends the nightly backup through the server, never in the browser alone', async () => {
    await rendre({ onglet: 'Instance' });

    const bascule = carte('Sauvegarde automatique').querySelector(
      'mat-slide-toggle button[role="switch"]',
    ) as HTMLButtonElement;
    bascule.click();
    await fixture.whenStable();

    expect(adminApi.setBackupsActive).toHaveBeenCalledExactlyOnceWith(false);
    expect(notify).toHaveBeenCalled();
  });
});
