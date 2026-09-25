// The four tabs of the Paramètres page (issue #606): where each card lives,
// what stays above the tabs, and the case a tab group gets wrong — a page that
// reads its query param once and never again.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { ParametresPage } from './parametres-page';
import type { FeasibilityReport } from '../../core/models';

const LEGAUX = {
  dureeHebdomadaireMaxMinutes: 48 * 60,
  dureeHebdomadaireMaxMineurMinutes: 35 * 60,
  reposQuotidienMinimalMinutes: 660,
  coupureRepasMinutes: 60,
  coupureRepasMidiDebut: '12:00:00',
  coupureRepasMidiFin: '14:00:00',
  coupureRepasSoirDebut: '19:00:00',
  coupureRepasSoirFin: '21:00:00',
  heureDebutSoiree: '20:00:00',
};

/** A dataset the pre-solve check found impossible: the banner then has something to say. */
const INFAISABLE: FeasibilityReport = {
  feasible: false,
  manqueAnimateurs: 3,
  causes: [],
  totalCauses: 0,
  causesCritiques: 0,
  causesElevees: 0,
  message: 'Il manque des animateurs le samedi.',
};

describe('ParametresPage — onglets', () => {
  let fixture: ComponentFixture<ParametresPage>;
  let params: BehaviorSubject<ParamMap>;

  async function rendre(options: { onglet?: string } = {}): Promise<void> {
    params = new BehaviorSubject<ParamMap>(
      convertToParamMap(options.onglet ? { onglet: options.onglet } : {}),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        {
          provide: AdminApi,
          useValue: {
            mailConfig: vi.fn(async () => ({ adminEmail: 'admin@exemple.test' })),
            backups: vi.fn(async () => null),
            // The reminders card renders nothing until the server answered.
            notificationSettings: vi.fn(async () => ({
              actives: false,
              heureEnvoi: '07:00:00',
              rappelVeille: true,
              relanceNonAccuses: true,
              signalementEchanges: true,
            })),
          },
        },
        { provide: ConstraintsApi, useValue: { legalParameters: vi.fn(async () => LEGAUX) } },
        { provide: AffichageMuralApi, useValue: { list: vi.fn(async () => []) } },
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
          useValue: {
            report: () => INFAISABLE,
            reloadFeasibility: vi.fn(async () => undefined),
          },
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
        {
          provide: SolverSettingsService,
          useValue: {
            refresh: vi.fn(async () => undefined),
            mailFinResolution: signal(false),
            setMailFinResolution: vi.fn(),
          },
        },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: PlanSnapshotStore, useValue: { capturer: vi.fn(async () => undefined) } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn(async () => undefined) } },
        { provide: ConfirmationRecopie, useValue: { demander: vi.fn(async () => true) } },
        {
          provide: ScenarioImportService,
          useValue: {
            importer: vi.fn(async () => ({ status: 'cancelled', result: null })),
            rechargerApresImport: vi.fn(async () => undefined),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: params.value }, queryParamMap: params },
        },
      ],
    });
    fixture = TestBed.createComponent(ParametresPage);
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

  beforeEach(async () => {
    await rendre();
  });

  it('names its five tabs and opens on the legal parameters', () => {
    expect(onglets()).toEqual([
      'Légaux',
      'Édition',
      'E-mails automatiques',
      'Affichage mural',
      'Globaux',
    ]);
    expect(textOf()).toContain('Paramètres légaux');
    expect(textOf()).toContain('Coupure repas');
    // And none of the other tabs' cards is on screen with them.
    expect(textOf()).not.toContain('Typologie ninja');
    expect(textOf()).not.toContain('Sauvegarde automatique');
  });

  it('puts each card under the tab that names it', async () => {
    await cliquerOnglet('Édition');
    expect(textOf()).toContain('Typologie ninja');
    expect(textOf()).toContain("Qualité d'organisation");
    expect(textOf()).toContain('Sur leur propre écran');

    await cliquerOnglet('E-mails automatiques');
    expect(textOf()).toContain("Prévenir par e-mail à la fin d'une résolution");
    expect(textOf()).toContain('Rappels et relances automatiques');

    await cliquerOnglet('Affichage mural');
    expect(textOf()).toContain('Aucun lien actif.');

    await cliquerOnglet('Globaux');
    expect(textOf()).toContain('Sauvegarde automatique');
    expect(textOf()).toContain('Export SQL');
  });

  /**
   * The router reuses this component when one navigates to `/parametres` again
   * with another `onglet` — from the menu, or from the « paramètres légaux »
   * link of Pauses while another tab is open.
   */
  it('follows the address instead of reading it once', async () => {
    params.next(convertToParamMap({ onglet: 'globaux' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(textOf()).toContain('Sauvegarde automatique');
  });

  it('opens on the default tab when the address names one it does not know', async () => {
    await rendre({ onglet: 'notifications' });

    expect(textOf()).toContain('Paramètres légaux');
  });

  /** It speaks of the edition, not of a tab: hiding it behind one would lose it. */
  it('keeps the feasibility banner above every tab', async () => {
    expect(racine().querySelector('app-feasibility-banner')).not.toBeNull();

    await cliquerOnglet('Globaux');
    expect(racine().querySelector('app-feasibility-banner')).not.toBeNull();
  });

  it('keeps the output panel under every tab', async () => {
    expect(racine().querySelector('app-output-panel')).not.toBeNull();

    await cliquerOnglet('Édition');
    expect(racine().querySelector('app-output-panel')).not.toBeNull();
  });

  /** One page title, then the cards: the two section headings the tabs replaced are gone. */
  it('leaves a valid heading hierarchy in each tab', async () => {
    for (const nom of ['Légaux', 'Édition', 'E-mails automatiques', 'Affichage mural', 'Globaux']) {
      await cliquerOnglet(nom);
      expect(racine().querySelectorAll('h1')).toHaveLength(1);
      expect(racine().querySelectorAll('h3')).toHaveLength(0);
      expect(racine().querySelectorAll('h2').length).toBeGreaterThan(0);
    }
  });
});
