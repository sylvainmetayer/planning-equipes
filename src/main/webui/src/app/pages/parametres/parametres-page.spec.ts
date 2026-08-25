// Two halves. The ninja picker logic (component created, never rendered), then
// the rendered page — this screen holds the two most destructive buttons of the
// application (replay a SQL dump, import a scenario over the current edition)
// and the one switch whose whole point is that it must *not* be usable when the
// server has no admin address: a toggle that silently does nothing is worse
// than no toggle.

import { provideZonelessChangeDetection, signal, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
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
import { ConfirmService } from '../../shared/confirm-dialog';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { ParametresPage } from './parametres-page';
import type { TypologieItem } from '../../core/models';

/** Reaches the protected members the template binds to. */
type PageInternals = {
  typologieNinjaId: Signal<string | null>;
  alerteNinjaManquant: Signal<string>;
  setNinja: (id: string | null) => Promise<void>;
};

describe('ParametresPage ninja picker', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    save: vi.fn(async () => true),
    reportError: vi.fn()
  };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.save.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        // The constructor loads the scenario list and the découpage
        // parameters; both answers are irrelevant to the picker under test.
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: EditionStore, useValue: { courant: () => null } },
        { provide: ProblemesStore, useValue: { report: () => null, reloadFeasibility: vi.fn(async () => undefined) } },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false, activeJobDescription: () => '' } },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: PlanSnapshotStore, useValue: { capturer: vi.fn(async () => undefined) } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn(async () => undefined) } },
        { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(typologies: TypologieItem[]): PageInternals {
    referenceData.typologies.set(typologies);
    return TestBed.createComponent(ParametresPage).componentInstance as unknown as PageInternals;
  }

  it('reads the current ninja typologie from the store', () => {
    const page = createPage([
      { id: 'STRATEGIE', label: 'Stratégie' },
      { id: 'JOKER', label: 'Joker', ninja: true }
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
    expect(createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]).alerteNinjaManquant()).toBe('');
  });

  it('promotes the selected typologie, letting the server demote the previous one', async () => {
    const page = createPage([
      { id: 'STRATEGIE', label: 'Stratégie' },
      { id: 'JOKER', label: 'Joker', ninja: true }
    ]);

    await page.setNinja('STRATEGIE');

    expect(crud.save).toHaveBeenCalledTimes(1);
    expect(crud.save).toHaveBeenCalledWith(
      'typologies',
      { id: 'STRATEGIE', label: 'Stratégie', ninja: true },
      'STRATEGIE',
      expect.anything()
    );
  });

  it('clears the flag on the current holder when "Aucune" is picked', async () => {
    const page = createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]);

    await page.setNinja(null);

    expect(crud.save).toHaveBeenCalledWith(
      'typologies',
      { id: 'JOKER', label: 'Joker', ninja: false },
      'JOKER',
      expect.anything()
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
  let api: {
    get: ReturnType<typeof vi.fn>;
    put: ReturnType<typeof vi.fn>;
    downloadGet: ReturnType<typeof vi.fn>;
    postRaw: ReturnType<typeof vi.fn>;
  };
  let confirm: { ask: ReturnType<typeof vi.fn> };
  let instantane: { proposer: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let setMailFinResolution: ReturnType<typeof vi.fn>;
  const mailFinResolution = signal(false);
  const editingLocked = signal(false);

  const PARAMETRES = {
    dureeVacationCibleMinutes: 240,
    dureeVacationMinMinutes: 120,
    dureeVacationMaxMinutes: 480,
    dureeChevauchementMinutes: 15,
    dureePauseRepasMinutes: 45,
    fenetreRepasMidiDebut: '11:30',
    fenetreRepasMidiFin: '14:00',
    fenetreRepasSoirDebut: '18:30',
    fenetreRepasSoirFin: '21:00',
    nombreFamillesDecalage: 1,
    dureeDecalageMaxMinutes: 60
  };

  async function rendre(options: { adminEmail?: string | null } = {}): Promise<void> {
    editingLocked.set(false);
    mailFinResolution.set(false);
    notify = vi.fn();
    setMailFinResolution = vi.fn(async (actif: boolean) => mailFinResolution.set(actif));
    confirm = { ask: vi.fn(async () => true) };
    instantane = { proposer: vi.fn(async () => undefined) };
    api = {
      get: vi.fn(async (url: string) => {
        if (url.includes('scenarios')) {
          return ['festival.yaml', 'festival-canicule.yaml'];
        }
        if (url.includes('parametres-decoupage')) {
          return { ...PARAMETRES };
        }
        if (url.includes('mail-config')) {
          return { adminEmail: options.adminEmail === undefined ? 'admin@exemple.test' : options.adminEmail };
        }
        return [];
      }),
      put: vi.fn(async (_url: string, body: unknown) => body),
      downloadGet: vi.fn(async () => 'Téléchargement démarré.'),
      postRaw: vi.fn(async () => ({ message: 'Base remplacée.' }))
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: { reload: vi.fn(async () => undefined), save: vi.fn(async () => true), reportError: vi.fn() } },
        { provide: EditionStore, useValue: { courant: () => null } },
        { provide: ProblemesStore, useValue: { report: () => null, reloadFeasibility: vi.fn(async () => undefined) } },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: {
            solverBusy: () => editingLocked(),
            editingLocked,
            activeJobDescription: () => 'Une résolution est en cours.'
          }
        },
        {
          provide: SolverSettingsService,
          useValue: { refresh: vi.fn(async () => undefined), mailFinResolution, setMailFinResolution }
        },
        { provide: NotificationService, useValue: { notify } },
        { provide: PlanSnapshotStore, useValue: { capturer: vi.fn(async () => undefined) } },
        { provide: InstantaneAvantAction, useValue: instantane },
        { provide: ConfirmService, useValue: confirm },
        {
          provide: ScenarioImportService,
          useValue: { importer: vi.fn(async () => ({ status: 'imported', result: null })), rechargerApresImport: vi.fn(async () => undefined) }
        }
      ]
    });
    fixture = TestBed.createComponent(ParametresPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function champ(name: string): HTMLInputElement {
    return racine().querySelector(`input[name="${name}"]`) as HTMLInputElement;
  }

  function saisir(name: string, valeur: string): void {
    const input = champ(name);
    input.value = valeur;
    input.dispatchEvent(new Event('input'));
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle)
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  /** The end-of-solve mail switch — Material renders it as a `role="switch"` button. */
  function interrupteur(): HTMLButtonElement {
    return racine().querySelector('mat-slide-toggle button[role="switch"]') as HTMLButtonElement;
  }

  /** A `File` jsdom can read: its own implementation has no `text()`. */
  function fichier(nom: string, contenu: string): File {
    const file = new File([contenu], nom);
    Object.defineProperty(file, 'text', { value: async () => contenu });
    return file;
  }

  /** The découpage preview sentence, rebuilt on every keystroke. */
  function apercu(): string {
    return racine().querySelector('form app-status-message')!.textContent!.replace(/\s+/g, ' ').trim();
  }

  it('fills the découpage form from the server and summarises it in one sentence', async () => {
    await rendre();

    expect(champ('dureeVacationCibleMinutes').value).toBe('240');
    expect(apercu()).toContain("des vacations d'environ 4 h");
    expect(apercu()).toContain('jamais plus de 8 h');
    expect(apercu()).toContain('pause repas de 45 min');
    // One grid: the "grilles décalées" clause is noise and must stay out.
    expect(apercu()).not.toContain('grilles décalées');
  });

  it('updates the summary while the settings are typed, before anything is saved', async () => {
    await rendre();

    saisir('dureeVacationCibleMinutes', '480');
    saisir('nombreFamillesDecalage', '3');
    await fixture.whenStable();

    // The preview is a computed over an immutable signal: an in-place mutation
    // would leave this sentence stale in a zoneless app.
    expect(apercu()).toContain("environ 8 h");
    expect(apercu()).toContain('réparties sur 3 grilles décalées');
    expect(api.put).not.toHaveBeenCalled();
  });

  it('saves the edited settings and says so', async () => {
    await rendre();

    saisir('dureePauseRepasMinutes', '60');
    await fixture.whenStable();
    racine().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.put).toHaveBeenCalledOnce();
    const [url, corps] = api.put.mock.calls[0] as unknown as [string, { dureePauseRepasMinutes: number }];
    expect(url).toBe('/api/parametres-decoupage');
    expect(corps.dureePauseRepasMinutes).toBe(60);
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('lists the scenarios and preselects one, so the load button always has a target', async () => {
    await rendre();

    expect(racine().querySelector('.scenario-select .mat-mdc-select-value')!.textContent!.trim()).toBe('festival.yaml');
    expect(bouton('Charger le scénario selectionné').disabled).toBe(false);
  });

  it('locks every destructive action of the edition while a solve runs on it', async () => {
    await rendre();
    editingLocked.set(true);
    await fixture.whenStable();

    expect(bouton('Charger le scénario selectionné').disabled).toBe(true);
    expect(bouton('Importer un fichier').disabled).toBe(true);
    expect(bouton('Enregistrer les paramètres').disabled).toBe(true);
    expect(bouton('Importer un dump SQL').disabled).toBe(true);
  });

  it('offers the end-of-solve mail when the server has an admin address', async () => {
    await rendre({ adminEmail: 'admin@exemple.test' });

    const toggle = interrupteur();
    expect(toggle.disabled).toBe(false);
    expect(racine().textContent!).toContain('Destinataire : admin@exemple.test');

    toggle.click();
    await fixture.whenStable();
    expect(setMailFinResolution).toHaveBeenCalledWith(true);
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('disables the mail switch, and explains why, when no admin address is configured', async () => {
    await rendre({ adminEmail: null });

    // A switch that flips and sends nothing is the failure this guards.
    expect(interrupteur().disabled).toBe(true);
    expect(racine().textContent!).toContain("Aucune adresse administrateur n'est configurée");
  });

  it('never replays a SQL dump without a confirmation and a snapshot offer', async () => {
    await rendre();
    confirm.ask.mockResolvedValue(false);

    const input = racine().querySelector('input[type="file"][accept^=".sql"]') as HTMLInputElement;
    const dump = fichier('sauvegarde.sql', '-- dump');
    Object.defineProperty(input, 'files', { configurable: true, value: [dump] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    // Refused: it replaces the whole database.
    expect(api.postRaw).not.toHaveBeenCalled();
    expect(instantane.proposer).not.toHaveBeenCalled();

    confirm.ask.mockResolvedValue(true);
    Object.defineProperty(input, 'files', { configurable: true, value: [dump] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(instantane.proposer).toHaveBeenCalledOnce();
    expect(api.postRaw).toHaveBeenCalledWith('/api/database/import', '-- dump', 'application/sql');
    expect(racine().textContent!).toContain('Base remplacée.');
  });

  it('exports the database as a file named after the deployment', async () => {
    await rendre();

    bouton('Exporter le dump SQL').click();
    await fixture.whenStable();

    const [url, filename] = api.downloadGet.mock.calls[0] as unknown as [string, string];
    expect(url).toBe('/api/database/export');
    expect(filename.endsWith('.sql')).toBe(true);
  });
});
