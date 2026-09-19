// Two halves. The ninja picker logic (component created, never rendered), then
// the rendered page — this screen holds the most destructive button of the
// application (replay a SQL dump, every edition included) and the one switch
// whose whole point is that it must *not* be usable when the server has no
// admin address: a toggle that silently does nothing is worse than no toggle.
//
// The scenario operations left this screen: they are covered by
// `scenario-preenregistre.spec.ts` (Débogage), `import-scenario-card.spec.ts`
// (Imports) and `exports-page.spec.ts`.

import { provideZonelessChangeDetection, signal, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { REPLACE_KEYWORD, ParametresPage } from './parametres-page';
import type { DemandeRecopie } from '../../shared/confirmation-recopie';
import type { EtatSauvegarde, TypologieItem } from '../../core/models';
import { seedStore } from '../../core/testing/seed-store';

/** Reaches the protected members the template binds to. */
type PageInternals = {
  typologieNinjaId: Signal<string | null>;
  alerteNinjaManquant: Signal<string>;
  setNinja: (id: string | null) => Promise<void>;
};

/** What the legal card reads on entry — the page under test only has to host it. */
const LEGAUX = {
  dureeHebdomadaireMaxMinutes: 48 * 60,
  dureeHebdomadaireMaxMineurMinutes: 35 * 60,
  pauseMinimaleEntreVacationsMinutes: 30,
  reposQuotidienMinimalMinutes: 660,
  pauseSurPoste: false,
  coupureRepasMinutes: 60,
  coupureRepasMidiDebut: '12:00:00',
  coupureRepasMidiFin: '14:00:00',
  coupureRepasSoirDebut: '19:00:00',
  coupureRepasSoirFin: '21:00:00',
  heureDebutSoiree: '20:00:00',
};

describe('ParametresPage ninja picker', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    save: vi.fn(async () => true),
    reportError: vi.fn(),
  };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.save.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        // The legal card loads its parameters on entry; the answer is
        // irrelevant to the picker under test.
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        {
          provide: AdminApi,
          useValue: {
            mailConfig: vi.fn(async () => ({ adminEmail: null })),
            backups: vi.fn(async () => null),
          },
        },
        { provide: ConstraintsApi, useValue: { legalParameters: vi.fn(async () => LEGAUX) } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: EditionStore, useValue: { courant: () => null } },
        {
          provide: ProblemesStore,
          useValue: { report: () => null, reloadFeasibility: vi.fn(async () => undefined) },
        },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: {
            solverBusy: () => false,
            editingLocked: () => false,
            activeJobDescription: () => '',
          },
        },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: PlanSnapshotStore, useValue: { capturer: vi.fn(async () => undefined) } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn(async () => undefined) } },
        { provide: ConfirmationRecopie, useValue: { demander: vi.fn(async () => true) } },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(typologies: TypologieItem[]): PageInternals {
    seedStore(referenceData, 'typologies', typologies);
    return TestBed.createComponent(ParametresPage).componentInstance as unknown as PageInternals;
  }

  it('reads the current ninja typologie from the store', () => {
    const page = createPage([
      { id: 'STRATEGIE', label: 'Stratégie' },
      { id: 'JOKER', label: 'Joker', ninja: true },
    ]);
    expect(page.typologieNinjaId()).toBe('JOKER');
  });

  it('reports no ninja when the referential has none', () => {
    const page = createPage([{ id: 'STRATEGIE', label: 'Stratégie' }]);
    expect(page.typologieNinjaId()).toBeNull();
  });

  it('warns when the referential has typologies but no ninja', () => {
    const page = createPage([{ id: 'STRATEGIE', label: 'Stratégie' }]);
    expect(page.alerteNinjaManquant()).not.toBe('');
  });

  it('stays silent on an empty referential and once a ninja is designated', () => {
    expect(createPage([]).alerteNinjaManquant()).toBe('');
    expect(createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]).alerteNinjaManquant()).toBe(
      '',
    );
  });

  it('promotes the selected typologie, letting the server demote the previous one', async () => {
    const page = createPage([
      { id: 'STRATEGIE', label: 'Stratégie' },
      { id: 'JOKER', label: 'Joker', ninja: true },
    ]);

    await page.setNinja('STRATEGIE');

    expect(crud.save).toHaveBeenCalledTimes(1);
    expect(crud.save).toHaveBeenCalledWith(
      'typologies',
      { id: 'STRATEGIE', label: 'Stratégie', ninja: true },
      'STRATEGIE',
      expect.anything(),
    );
  });

  it('clears the flag on the current holder when "Aucune" is picked', async () => {
    const page = createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]);

    await page.setNinja(null);

    expect(crud.save).toHaveBeenCalledWith(
      'typologies',
      { id: 'JOKER', label: 'Joker', ninja: false },
      'JOKER',
      expect.anything(),
    );
  });

  it('does nothing when the selection did not change', async () => {
    const page = createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]);

    await page.setNinja('JOKER');

    expect(crud.save).not.toHaveBeenCalled();
  });

  it('does nothing when clearing a referential that has no ninja', async () => {
    const page = createPage([{ id: 'STRATEGIE', label: 'Stratégie' }]);

    await page.setNinja(null);

    expect(crud.save).not.toHaveBeenCalled();
  });
});

describe('ParametresPage rendering', () => {
  let fixture: ComponentFixture<ParametresPage>;
  let api: { get: ReturnType<typeof vi.fn> };
  let adminApi: {
    mailConfig: ReturnType<typeof vi.fn>;
    backups: ReturnType<typeof vi.fn>;
    setBackupsActive: ReturnType<typeof vi.fn>;
    exportDatabase: ReturnType<typeof vi.fn>;
    importDatabase: ReturnType<typeof vi.fn>;
  };
  let constraintsApi: { legalParameters: ReturnType<typeof vi.fn> };
  let recopie: { demander: ReturnType<typeof vi.fn> };
  let instantane: { proposer: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let setMailFinResolution: ReturnType<typeof vi.fn>;
  const mailFinResolution = signal(false);
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
  };

  async function rendre(
    options: {
      adminEmail?: string | null;
      sauvegarde?: EtatSauvegarde;
      /** Which tab to open; the page opens on « Légaux » (issue #606). */
      onglet?: 'Édition' | 'E-mails automatiques' | 'Globaux';
    } = {},
  ): Promise<void> {
    editingLocked.set(false);
    mailFinResolution.set(false);
    notify = vi.fn();
    setMailFinResolution = vi.fn(async (actif: boolean) => mailFinResolution.set(actif));
    constraintsApi = { legalParameters: vi.fn(async () => LEGAUX) };
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
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: AdminApi, useValue: adminApi },
        { provide: ConstraintsApi, useValue: constraintsApi },
        {
          provide: ReferenceCrudService,
          useValue: {
            reload: vi.fn(async () => undefined),
            save: vi.fn(async () => true),
            reportError: vi.fn(),
          },
        },
        { provide: EditionStore, useValue: { courant: () => null } },
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
        {
          provide: SolverSettingsService,
          useValue: {
            refresh: vi.fn(async () => undefined),
            mailFinResolution,
            setMailFinResolution,
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

  /**
   * The end-of-solve mail switch — Material renders it as a `role="switch"`
   * button; found in its own card, since the legal card carries a switch too.
   */
  function interrupteur(): HTMLButtonElement {
    return carte('Prévenir par e-mail').querySelector(
      'mat-slide-toggle button[role="switch"]',
    ) as HTMLButtonElement;
  }

  /** One card of the page, found by its heading — several now carry a switch. */
  function carte(titre: string): HTMLElement {
    const trouve = Array.from(racine().querySelectorAll('mat-card')).find((each) =>
      each.textContent!.includes(titre),
    );
    expect(trouve, `carte « ${titre} » absente`).toBeDefined();
    return trouve as HTMLElement;
  }

  function text(element: HTMLElement): string {
    return element.textContent!.replace(/\s+/g, ' ').trim();
  }

  /** A `File` jsdom can read: its own implementation has no `text()`. */
  function file(nom: string, contenu: string): File {
    const created = new File([contenu], nom);
    Object.defineProperty(created, 'text', { value: async () => contenu });
    return created;
  }

  it('locks the SQL dump replay while a solve runs', async () => {
    await rendre({ onglet: 'Globaux' });
    editingLocked.set(true);
    await fixture.whenStable();

    expect(bouton('Importer un dump SQL').disabled).toBe(true);
  });

  it('sends the reader to the screens the scenario operations moved to', async () => {
    await rendre({ onglet: 'Édition' });

    const renvois = text(carte('Sur leur propre écran'));
    expect(renvois).toContain('page Imports, onglet Scénario');
    expect(renvois).toContain('page Exports');
    expect(renvois).toContain('page Débogage');
  });

  it('offers the end-of-solve mail when the server has an admin address', async () => {
    await rendre({ adminEmail: 'admin@exemple.test', onglet: 'E-mails automatiques' });

    const toggle = interrupteur();
    expect(toggle.disabled).toBe(false);
    expect(racine().textContent!).toContain('Destinataire : admin@exemple.test');

    toggle.click();
    await fixture.whenStable();
    expect(setMailFinResolution).toHaveBeenCalledWith(true);
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('disables the mail switch, and explains why, when no admin address is configured', async () => {
    await rendre({ adminEmail: null, onglet: 'E-mails automatiques' });

    // A switch that flips and sends nothing is the failure this guards.
    expect(interrupteur().disabled).toBe(true);
    expect(racine().textContent!).toContain("Aucune adresse administrateur n'est configurée");
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
    await rendre({ onglet: 'Globaux' });
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
    await rendre({ onglet: 'Globaux' });

    choisirDump();
    await fixture.whenStable();

    const demande = recopie.demander.mock.calls[0][0] as DemandeRecopie;
    expect(demande.valeurAttendue).toBe(REPLACE_KEYWORD);
    expect(demande.message).toContain('toutes les éditions sont écrasées');
    expect(demande.message).toContain('sauvegarde.sql');
  });

  it('exports the database as a file named after the deployment', async () => {
    await rendre({ onglet: 'Globaux' });

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
    await rendre({ onglet: 'Globaux' });

    const contenu = text(carte('Sauvegarde automatique'));
    expect(contenu).toContain('/backups');
    expect(contenu).toContain('10 sauvegardes');
    expect(contenu).toContain('planning-20260308-040000.dump');
    expect(contenu).toContain('5.0 Mo');
  });

  it('reports the last failed night instead of looking idle', async () => {
    await rendre({
      onglet: 'Globaux',
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

  it('says the feature is inert when the deployment configured no directory', async () => {
    await rendre({
      onglet: 'Globaux',
      sauvegarde: { ...SAUVEGARDE, configured: false, directory: null, files: [], nextRun: null },
    });

    const contenu = text(carte('Sauvegarde automatique'));
    expect(contenu).toContain('BACKUP_DIR');
    // No switch to flip: turning one on would promise a backup nothing writes.
    expect(carte('Sauvegarde automatique').querySelector('mat-slide-toggle')).toBeNull();
  });

  it('suspends the nightly backup through the server, never in the browser alone', async () => {
    await rendre({ onglet: 'Globaux' });

    const bascule = carte('Sauvegarde automatique').querySelector(
      'mat-slide-toggle button[role="switch"]',
    ) as HTMLButtonElement;
    bascule.click();
    await fixture.whenStable();

    expect(adminApi.setBackupsActive).toHaveBeenCalledExactlyOnceWith(false);
    expect(notify).toHaveBeenCalled();
  });
});
