// The three tabs of the Paramètres page (issues #606, #720): where each card lives,
// what stays above the tabs, and the case a tab group gets wrong — a page that
// reads its query param once and never again.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { AdminApi } from '../../core/api/admin-api';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { EchangesApi } from '../../core/api/echanges-api';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { ParametresPage } from './parametres-page';
import type { FeasibilityReport } from '../../core/models';

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

  async function rendre(options: { onglet?: string; focus?: string } = {}): Promise<void> {
    params = new BehaviorSubject<ParamMap>(
      convertToParamMap({
        ...(options.onglet ? { onglet: options.onglet } : {}),
        ...(options.focus ? { focus: options.focus } : {}),
      }),
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
              heureRappelVeille: '18:00:00',
              delaiRelanceHeures: 72,
              ancienneteEchangeJours: 3,
            })),
            organisationContact: vi.fn(async () => ({ telephone: null, email: null })),
          },
        },
        {
          provide: DisponibilitesApi,
          useValue: {
            configuration: vi.fn(async () => ({ collecteOuverte: true, debut: null, fin: null })),
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
          },
        },
        { provide: AffichageMuralApi, useValue: { list: vi.fn(async () => []) } },
        {
          provide: ReferenceCrudService,
          useValue: {
            reload: vi.fn(async () => undefined),
            save: vi.fn(async () => true),
            reportError: vi.fn(),
          },
        },
        { provide: EditionStore, useValue: { courant: () => null, reload: vi.fn() } },
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

  it('names its three tabs and opens on the edition', () => {
    expect(onglets()).toEqual(['Édition', 'Affichage mural', 'Instance']);
    expect(textOf()).toContain("Nom de l'édition");
    expect(textOf()).toContain('Gel du référentiel');
    expect(textOf()).toContain('Guichets');
    expect(textOf()).toContain('Rappels et relances automatiques');
    expect(textOf()).toContain("Contact de l'organisation");
    // What decides the plan is not here any more: one line points to it.
    expect(textOf()).not.toContain('Paramètres légaux');
    expect(textOf()).not.toContain('Typologie ninja');
    expect(textOf()).not.toContain('Sauvegarde automatique');
  });

  it('puts each card under the tab that names it', async () => {
    await cliquerOnglet('Affichage mural');
    expect(textOf()).toContain('Aucun lien actif.');

    await cliquerOnglet('Instance');
    expect(textOf()).toContain('Sauvegarde automatique');
    expect(textOf()).toContain('Export SQL');
    expect(textOf()).toContain('Raccourcis clavier');
  });

  /** The covoiturage has no switch of its own: it says it follows the collection. */
  it('says the covoiturage opens and closes with the collection', () => {
    expect(textOf()).toContain('Ouvert avec la collecte');
  });

  /**
   * The router reuses this component when one navigates to `/parametres` again
   * with another `onglet` — from the menu, or from the « paramètres légaux »
   * link of Pauses while another tab is open.
   */
  it('follows the address instead of reading it once', async () => {
    params.next(convertToParamMap({ onglet: 'instance' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(textOf()).toContain('Sauvegarde automatique');
  });

  /** « Globaux » became « Instance »: a bookmark of the old name lands on the same cards. */
  it('reads the former name of the instance tab', async () => {
    await rendre({ onglet: 'globaux' });

    expect(textOf()).toContain('Sauvegarde automatique');
  });

  /** The toolbar's hourglass names the field, not the tab: the page opens where it lives. */
  it('opens the instance tab for the simulated clock field', async () => {
    await rendre({ focus: 'date-du-jour' });

    expect(textOf()).toContain('Sauvegarde automatique');
    expect(racine().querySelector('app-horloge-simulee-card')).not.toBeNull();
  });

  it('opens on the default tab when the address names one it does not know', async () => {
    await rendre({ onglet: 'notifications' });

    expect(textOf()).toContain('Guichets');
  });

  /** It speaks of the edition, not of a tab: hiding it behind one would lose it. */
  it('keeps the feasibility banner above every tab', async () => {
    expect(racine().querySelector('app-feasibility-banner')).not.toBeNull();

    await cliquerOnglet('Instance');
    expect(racine().querySelector('app-feasibility-banner')).not.toBeNull();
  });

  it('keeps the output panel under every tab', async () => {
    expect(racine().querySelector('app-output-panel')).not.toBeNull();

    await cliquerOnglet('Instance');
    expect(racine().querySelector('app-output-panel')).not.toBeNull();
  });

  /** One page title, then the cards: a third level only inside a card that has a title. */
  it('leaves a valid heading hierarchy in each tab', async () => {
    for (const nom of ['Édition', 'Affichage mural', 'Instance']) {
      await cliquerOnglet(nom);
      expect(racine().querySelectorAll('h1')).toHaveLength(1);
      expect(racine().querySelectorAll('h2').length).toBeGreaterThan(0);
      for (const h3 of Array.from(racine().querySelectorAll('h3'))) {
        expect(h3.closest('mat-card')?.querySelector('h2')).not.toBeNull();
      }
    }
  });
});
