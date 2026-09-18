// The four tabs of the Débogage page (issue #606): where each card lives, what
// the address says, and the two cases a tab group gets wrong — a page that
// reads its query param once and never again, and a tab that disappears with
// the one card the deployment does not allow.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { DateMockService } from '../../core/date-mock.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { DebugPage } from './debug-page';

describe('DebugPage — onglets', () => {
  let fixture: ComponentFixture<DebugPage>;
  let params: BehaviorSubject<ParamMap>;

  async function rendre(
    options: { onglet?: string; dateModifiable?: boolean } = {},
  ): Promise<void> {
    params = new BehaviorSubject<ParamMap>(
      convertToParamMap(options.onglet ? { onglet: options.onglet } : {}),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ApiService,
          useValue: { get: vi.fn(async () => ({ adminEmail: null })), post: vi.fn() },
        },
        {
          provide: DateMockService,
          useValue: {
            dateDuJour: signal(''),
            heureMock: signal(''),
            modifiable: signal(options.dateModifiable ?? true),
            actif: () => false,
            set: vi.fn(),
          },
        },
        { provide: EditionStore, useValue: { courant: () => null } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: ConfirmationRecopie, useValue: { demander: vi.fn() } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn() } },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: ReferenceDataStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: ProblemesStore, useValue: { reloadFeasibility: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: {
            solverBusy: () => false,
            editingLocked: () => false,
            activeJobDescription: () => '',
          },
        },
        { provide: PlanningApi, useValue: { scenarioNames: vi.fn(async () => []) } },
        {
          provide: ScenarioImportService,
          useValue: {
            importer: vi.fn(async () => ({ status: 'cancelled', result: null })),
            recapitulatif: vi.fn(() => ''),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: params.value }, queryParamMap: params },
        },
      ],
    });
    fixture = TestBed.createComponent(DebugPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function texte(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  /** The tab buttons, by the label a reader sees — the icon's ligature text apart. */
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

  it('names its four tabs and opens on the raw analysis', () => {
    expect(onglets()).toEqual(['Résolution', 'Vérifications', 'Données', 'Validateur YAML']);
    expect(texte()).toContain('Dernière analyse');
    expect(texte()).toContain("Documentation de l'API");
    // And none of the other tabs' cards is on screen with it.
    expect(texte()).not.toContain('Base de données');
    expect(texte()).not.toContain('Validateur YAML (');
  });

  it('puts each card under the tab that names it', async () => {
    await cliquerOnglet('Vérifications');
    expect(texte()).toContain('Envoyer un mail de test');
    expect(texte()).toContain('Mailpit');
    expect(texte()).toContain('Date du jour');

    await cliquerOnglet('Données');
    expect(texte()).toContain('Vider la base de données');
    expect(texte()).toContain('Scénarios');

    await cliquerOnglet('Validateur YAML');
    expect(racine().querySelector('app-yaml-validator')).not.toBeNull();
  });

  /**
   * The router reuses this component when one navigates to `/debug` again with
   * another `onglet` — from the menu, from the date-frozen indicator. Read once
   * in the constructor, the address changed and the screen did not, until an F5.
   */
  it('follows the address instead of reading it once', async () => {
    params.next(convertToParamMap({ onglet: 'donnees' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(texte()).toContain('Vider la base de données');
  });

  it('opens on the default tab when the address names one it does not know', async () => {
    await rendre({ onglet: 'validateur-yaml' });

    expect(texte()).toContain('Dernière analyse');
  });

  /**
   * The frozen date is a development-only control. Its card disappears where
   * the server forbids it; the tab it sits on holds four other checks and has
   * no business disappearing with it.
   */
  it('keeps the checks tab where the frozen date is not allowed', async () => {
    await rendre({ onglet: 'verifications', dateModifiable: false });

    expect(onglets()).toContain('Vérifications');
    expect(texte()).toContain('Envoyer un mail de test');
    expect(texte()).not.toContain('Date du jour');
  });

  /** The output panel answers an action of one tab and is read after it. */
  it('keeps the output panel under every tab', async () => {
    expect(racine().querySelector('app-output-panel')).not.toBeNull();

    await cliquerOnglet('Données');
    expect(racine().querySelector('app-output-panel')).not.toBeNull();
  });
});
