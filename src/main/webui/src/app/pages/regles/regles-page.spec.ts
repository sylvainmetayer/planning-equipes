// « Règles du planning »: rendered for real on mocked APIs. What is pinned is
// what the screen decides beyond showing a catalogue — a rule is named by its
// short label, never its technical name; nothing is written before
// « Enregistrer »; a threshold is saved over a fresh read of its record; a
// protected rule is switched off only once confirmed; `?regle=` opens the
// rule's panel on its own tab.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSlideToggle, MatSlideToggleChange } from '@angular/material/slide-toggle';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { EditionStore } from '../../core/edition.store';
import { ConstraintView, ConstraintsView, ParametresLegaux } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { LegalDisableConfirmService } from './legal-disable-dialog';
import { ReglesPage } from './regles-page';

function rule(overrides: Partial<ConstraintView>): ConstraintView {
  return {
    name: 'r',
    niveau: 'HARD',
    categorie: 'Affectation',
    description: '',
    actif: true,
    protegee: false,
    legale: false,
    dosable: false,
    poids: 1,
    score: '0hard/0medium/0soft',
    matchCount: 0,
    violations: [],
    postesEvalues: null,
    plancher: null,
    references: [],
    parametres: [],
    ...overrides,
  };
}

const HEBDO = rule({
  name: 'dureeHebdomadaireMax',
  libelleCourt: 'Durée hebdomadaire',
  categorie: 'Légal (temps de travail)',
  description: 'Au plus 48 h par semaine (art. L3121-20).',
  protegee: true,
  legale: true,
  parametres: [
    {
      libelle: 'Durée hebdomadaire maximale, majeurs',
      valeur: '48 h',
      lien: '/regles',
      onglet: 'legal',
      cle: 'dureeHebdomadaireMaxMinutes',
    },
  ],
});

const PLACES = rule({ name: 'posteDoitEtrePourvu', libelleCourt: 'Places pourvues' });

const CHARGE = rule({
  name: 'equilibrerCharge',
  libelleCourt: 'Charge équilibrée',
  niveau: 'MEDIUM',
  categorie: "Qualité d'organisation",
  dosable: true,
  poids: 5,
  remediation: 'Cherchez qui est très au-dessus.',
});

const LEGAUX = {
  dureeHebdomadaireMaxMinutes: 2880,
  dureeHebdomadaireMaxMineurMinutes: 2100,
  reposQuotidienMinimalMinutes: 660,
  dureePauseMinutes: 30,
  coupureRepasMinutes: 60,
  coupureRepasMidiDebut: '12:00:00',
  coupureRepasMidiFin: '14:00:00',
  coupureRepasSoirDebut: '19:00:00',
  coupureRepasSoirFin: '21:00:00',
  heureDebutSoiree: '20:00:00',
  dureeVacationMaxMinutes: 360,
} as ParametresLegaux;

function catalogue(contraintes: ConstraintView[]): ConstraintsView {
  return {
    analysedAt: null,
    scoreGlobal: null,
    postesNonPourvus: null,
    faisabilite: null,
    hardScore: null,
    contraintesAdHocEnCause: [],
    scoreHorsPlancher: null,
    plancherMedium: null,
    plancherSoft: null,
    pivotEcarts: [],
    lecture: [],
    contraintes,
  };
}

type Internals = {
  toggle: (constraint: ConstraintView, event: MatSlideToggleChange) => Promise<void>;
  setImportance: (constraint: ConstraintView, importance: 'FAIBLE' | 'NORMALE' | 'FORTE') => void;
  save: () => Promise<void>;
  dirty: () => boolean;
  onglet: () => string;
};

describe('ReglesPage', () => {
  let fixture: ComponentFixture<ReglesPage>;
  let constraintsApi: Record<string, ReturnType<typeof vi.fn>>;
  let allowsDisabling: ReturnType<typeof vi.fn>;
  let ask: ReturnType<typeof vi.fn>;

  async function rendre(
    params: Record<string, string> = {},
    contraintes: ConstraintView[] = [PLACES, HEBDO, CHARGE],
  ): Promise<void> {
    constraintsApi = {
      catalogue: vi.fn(async () => catalogue(contraintes)),
      legalParameters: vi.fn(async () => ({ ...LEGAUX })),
      qualityParameters: vi.fn(async () => ({ joursConsecutifsMax: 8 })),
      saveLegalParameters: vi.fn(async (legaux: ParametresLegaux) => legaux),
      saveQualityParameters: vi.fn(async (qualite: unknown) => qualite),
      setActive: vi.fn(async () => ({ actif: false })),
      setWeight: vi.fn(async (_name: string, poids: number) => ({ poids })),
      history: vi.fn(async () => ({ changes: [], resolutions: [] })),
      diagnose: vi.fn(),
    };
    allowsDisabling = vi.fn(async () => false);
    ask = vi.fn(async () => true);
    const queryParams = new BehaviorSubject<ParamMap>(convertToParamMap(params));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: ConstraintsApi, useValue: constraintsApi },
        { provide: LegalDisableConfirmService, useValue: { allowsDisabling } },
        { provide: ConfirmService, useValue: { ask } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: ProblemesStore,
          useValue: { shareConstraints: vi.fn(), alerteReglesLegales: () => '' },
        },
        {
          provide: SolverJobService,
          useValue: {
            onResult: () => () => undefined,
            editingLocked: () => false,
            solverBusy: () => false,
            activeJob: signal(null),
          },
        },
        {
          provide: PlanningResolutionStore,
          useValue: { reload: vi.fn(async () => undefined), resolution: () => null },
        },
        {
          provide: SolverSettingsService,
          useValue: {
            refresh: vi.fn(async () => undefined),
            bounds: signal(null),
            dureeResolutionSecondes: signal(null),
            plateauSecondes: signal(null),
            mailFinResolution: signal(false),
            secondsLimit: () => 900,
            effectivePlateauSeconds: () => 300,
          },
        },
        { provide: AdminApi, useValue: { mailConfig: vi.fn(async () => ({ adminEmail: null })) } },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'E1' }) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: queryParams.value, fragment: null },
            queryParamMap: queryParams,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ReglesPage);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(racine().querySelectorAll('tbody tr').length).toBeGreaterThan(0);
    });
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function page(): Internals {
    return fixture.componentInstance as unknown as Internals;
  }

  function rowNames(): string[] {
    return Array.from(racine().querySelectorAll('tbody th[scope="row"]')).map((each) =>
      each.textContent!.trim(),
    );
  }

  beforeEach(async () => {
    await rendre();
  });

  it('lists the hard rules on « Légal », by their short label, the law first', () => {
    expect(rowNames()).toEqual(['Durée hebdomadaire', 'Places pourvues']);
    expect(racine().textContent).not.toContain('dureeHebdomadaireMax');
    expect(racine().textContent).not.toContain('posteDoitEtrePourvu');
    // The article column: the citation for a legal rule, the category otherwise.
    expect(racine().querySelector('tbody tr .regles-article')?.textContent).toContain('L3121-20');
  });

  it('edits a threshold on its row and saves it over a fresh read of the record', async () => {
    const champ = racine().querySelector(
      'input[aria-label^="Durée hebdomadaire maximale, majeurs"]',
    ) as HTMLInputElement;
    expect(champ.value).toBe('48');
    constraintsApi['legalParameters'].mockResolvedValue({ ...LEGAUX, heureDebutSoiree: '19:00' });

    champ.value = '44';
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(page().dirty()).toBe(true);
    expect(constraintsApi['saveLegalParameters']).not.toHaveBeenCalled();

    await page().save();

    expect(constraintsApi['saveLegalParameters']).toHaveBeenCalledExactlyOnceWith({
      ...LEGAUX,
      heureDebutSoiree: '19:00',
      dureeHebdomadaireMaxMinutes: 44 * 60,
    });
    expect(page().dirty()).toBe(false);
  });

  it('keeps a protected rule on when the confirmation is refused', async () => {
    const event = new MatSlideToggleChange({ checked: false } as unknown as MatSlideToggle, false);

    await page().toggle(HEBDO, event);

    expect(allowsDisabling).toHaveBeenCalledOnce();
    expect(event.source.checked).toBe(true);
    expect(page().dirty()).toBe(false);
  });

  it('writes an importance only on « Enregistrer », as its weight', async () => {
    page().setImportance(CHARGE, 'FORTE');
    expect(constraintsApi['setWeight']).not.toHaveBeenCalled();

    await page().save();

    expect(constraintsApi['setWeight']).toHaveBeenCalledExactlyOnceWith('equilibrerCharge', 25);
  });

  it('opens the panel of the rule the address names, on that rule’s tab', async () => {
    await rendre({ regle: 'equilibrerCharge' });

    expect(page().onglet()).toBe('qualite');
    expect(rowNames()).toEqual(['Charge équilibrée']);
    const panneau = racine().querySelector('.regles-panneau') as HTMLElement;
    expect(panneau.textContent).toContain('Charge équilibrée');
    expect(panneau.textContent).toContain('Cherchez qui est très au-dessus.');
  });

  it('names a weight outside the three positions « personnalisée »', async () => {
    await rendre({ onglet: 'qualite' }, [{ ...CHARGE, poids: 7 }]);

    expect(racine().querySelector('.regles-personnalise')?.textContent).toContain('7');
  });

  it('asks before leaving with changes pending, and not otherwise', async () => {
    await expect(fixture.componentInstance.canLeave()).resolves.toBe(true);
    expect(ask).not.toHaveBeenCalled();

    page().setImportance(CHARGE, 'FAIBLE');

    await fixture.componentInstance.canLeave();
    expect(ask).toHaveBeenCalledOnce();
  });
});
